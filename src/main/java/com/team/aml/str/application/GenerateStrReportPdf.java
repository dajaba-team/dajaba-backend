package com.team.aml.str.application;

import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.str.domain.ReportPdfContent;
import com.team.aml.str.domain.ReportPdfRenderer;
import com.team.aml.str.domain.ReportPdfStatus;
import com.team.aml.str.domain.ReportPdfStorage;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 확정본 PDF 만들기·보관 (서식 회의 10/30 결정 2) — 모의 제출(P4)·다시 만들기(P5) 커밋 뒤에 {@link StrReportPdfTrigger} 가 부른다.
 * 승인 때 고정한 스냅샷 + 문서번호·보고일자로 한 번 만들고, 실패해도 승인·제출은 되돌리지 않는다(pdf_status FAILED → 승인자가 P5).
 * 읽기 시점의 pdf_attempts 로 시도를 구분해 같은 시도의 중복 실행만 막는다.
 * 다시 만들기 뒤에 도착한 옛 시도의 성공·실패 결과는 버린다(11/25 전체 점검).
 *
 * <p>트랜잭션을 셋으로 나눈다 — PDF 를 그리고 파일을 쓰는 동안 DB 연결을 잡지 않는다(운영 구성안 원칙 2, 외부 작업 중 트랜잭션 열지 않기).
 * <ol>
 *   <li>읽기: 보고서가 PENDING 이면 재료(ReportPdfContent)와 보관 키를 꺼낸다. PENDING 이 아니면 할 일 없음(이미 끝났거나 다른 쪽이 함)</li>
 *   <li>밖에서: 그리기 → SHA-256 → 보관</li>
 *   <li>기록: 다시 읽어 같은 시도가 아직 PENDING 이면 READY(키·해시·크기·템플릿 버전·생성 시각) 또는 FAILED(코드). @Version 이 겹친 쓰기를 막는다</li>
 * </ol>
 * 세 트랜잭션 모두 REQUIRES_NEW — 부르는 쪽 트랜잭션에 끼지 않는다. 트리거의 afterCommit 안에서는 끝난 트랜잭션의 연결·동기화가
 * 아직 묶여 있어, 기본(REQUIRED)이면 그 끝난 트랜잭션에 합류해 FAILED(NOT_STARTED) 가 커밋되지 않는다
 * (TransactionSynchronization#afterCommit 문서 권고, #149 리뷰). 뒤 스레드에서 부를 때는 어차피 새 트랜잭션이라 같다.
 *
 * <p>실패 코드(pdf_failure_code)에는 본문·계좌 값을 넣지 않는다 — RENDER_FAILED·STORAGE_FAILED·NOT_STARTED 처럼 단계 이름만.
 * 로그도 초안 번호·예외 종류만 (NFR-COM-04).
 */
@Service
public class GenerateStrReportPdf {

    private static final Logger log = LoggerFactory.getLogger(GenerateStrReportPdf.class);

    static final String RENDER_FAILED = "RENDER_FAILED";
    static final String STORAGE_FAILED = "STORAGE_FAILED";
    static final String NOT_STARTED = "NOT_STARTED";
    public static final String QUEUE_GIVE_UP = "QUEUE_GIVE_UP";

    private final StrReportRepository reports;
    private final ReportPdfRenderer renderer;
    private final ReportPdfStorage storage;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    /** 같은 JVM에서 로컬 fallback과 큐 소비가 겹치면 같은 초안·시도는 한 번만 그린다. */
    private final Set<Attempt> runningAttempts = ConcurrentHashMap.newKeySet();

    public GenerateStrReportPdf(StrReportRepository reports, ReportPdfRenderer renderer, ReportPdfStorage storage,
                                PlatformTransactionManager transactionManager) {
        this.reports = reports;
        this.renderer = renderer;
        this.storage = storage;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 이번에 남긴 상태. 기록할 결과가 없으면 empty */
    public Optional<ReportPdfStatus> execute(long draftId) {
        Optional<Job> job = readTx.execute(s -> pending(draftId).map(r -> {
            int attempts = r.getPdfAttempts();
            return new Job(new Attempt(draftId, attempts), ReportPdfContent.submitted(r),
                    ReportPdfStorage.keyOf(r.getReportNo(), attempts + 1));
        }));
        if (job == null || job.isEmpty()) {
            return Optional.empty();
        }

        Job current = job.get();
        if (!runningAttempts.add(current.attempt())) {
            log.info("확정본 PDF 중복 작업 건너뜀 draft_no={}", DisplayNumber.draftNo(draftId));
            return Optional.empty();
        }
        try {
            return executeOnce(current);
        } finally {
            runningAttempts.remove(current.attempt());
        }
    }

    private Optional<ReportPdfStatus> executeOnce(Job job) {
        Attempt attempt = job.attempt();
        long draftId = attempt.draftId();
        byte[] pdf;
        try {
            pdf = renderer.render(job.content());
        } catch (RuntimeException e) {
            log.error("확정본 PDF 그리기 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
            return failAttempt(attempt, RENDER_FAILED);
        }
        String sha256 = StrPdfDigest.sha256(pdf);
        try {
            storage.put(job.key(), pdf);
        } catch (RuntimeException e) {
            log.error("확정본 PDF 보관 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
            return failAttempt(attempt, STORAGE_FAILED);
        }

        Optional<ReportPdfStatus> result = recordAttempt(attempt, r ->
                r.pdfReady(job.key(), sha256, pdf.length, renderer.templateVersion(), Instant.now()));
        if (result.isPresent()) {
            log.info("확정본 PDF 보관 draft_no={} bytes={}", DisplayNumber.draftNo(draftId), pdf.length);
        }
        return result;
    }

    private Optional<ReportPdfStatus> failAttempt(Attempt attempt, String code) {
        return recordAttempt(attempt, r -> r.pdfFailed(code));
    }

    private Optional<ReportPdfStatus> recordAttempt(Attempt attempt, Consumer<StrReport> update) {
        try {
            Optional<ReportPdfStatus> result = writeTx.execute(s -> pendingAttempt(attempt).map(r -> {
                update.accept(r);
                reports.save(r);
                return r.getPdfStatus();
            }));
            if (result.isPresent()) {
                return result;
            }
        } catch (OptimisticLockingFailureException e) {
            // 재조회와 커밋 사이에 다시 만들기가 끝난 경우에도 옛 결과를 버린다.
            if (Boolean.TRUE.equals(readTx.execute(s -> pendingAttempt(attempt).isPresent()))) {
                throw e;
            }
        }
        log.info("확정본 PDF 옛 시도 결과 버림 draft_no={}", DisplayNumber.draftNo(attempt.draftId()));
        return Optional.empty();
    }

    /**
     * PENDING 이면 FAILED(code) 로. 작업을 시작조차 못 했을 때(실행기 거절)도 부른다 — PENDING 이 남으면 P5 로도 못 살린다.
     * @return FAILED 로 바꿨으면 FAILED, 이미 PENDING 이 아니면 empty
     */
    public Optional<ReportPdfStatus> fail(long draftId, String code) {
        Boolean done = writeTx.execute(s -> pending(draftId).map(r -> {
            r.pdfFailed(code);
            reports.save(r);
            return true;
        }).orElse(false));
        return Boolean.TRUE.equals(done) ? Optional.of(ReportPdfStatus.FAILED) : Optional.empty();
    }

    private Optional<StrReport> pending(long draftId) {
        return reports.findByDraftId(draftId).filter(r -> r.getPdfStatus() == ReportPdfStatus.PENDING);
    }

    private Optional<StrReport> pendingAttempt(Attempt attempt) {
        return pending(attempt.draftId()).filter(r -> r.getPdfAttempts() == attempt.attempts());
    }

    private record Attempt(long draftId, int attempts) {}
    private record Job(Attempt attempt, ReportPdfContent content, String key) {}
}
