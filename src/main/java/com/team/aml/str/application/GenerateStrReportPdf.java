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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * 확정본 PDF 만들기·보관 (서식 회의 10/30 결정 2) — 모의 제출(P4)·다시 만들기(P5) 커밋 뒤에 {@link StrReportPdfTrigger} 가 부른다.
 * 승인 때 고정한 스냅샷 + 문서번호·보고일자로 한 번 만들고, 실패해도 승인·제출은 되돌리지 않는다(pdf_status FAILED → 승인자가 P5).
 *
 * <p>트랜잭션을 셋으로 나눈다 — PDF 를 그리고 파일을 쓰는 동안 DB 연결을 잡지 않는다(운영 구성안 원칙 2, 외부 작업 중 트랜잭션 열지 않기).
 * <ol>
 *   <li>읽기: 보고서가 PENDING 이면 재료(ReportPdfContent)와 보관 키를 꺼낸다. PENDING 이 아니면 할 일 없음(이미 끝났거나 다른 쪽이 함)</li>
 *   <li>밖에서: 그리기 → SHA-256 → 보관</li>
 *   <li>기록: 다시 읽어 아직 PENDING 이면 READY(키·해시·크기·템플릿 버전·생성 시각) 또는 FAILED(코드). @Version 이 겹친 쓰기를 막는다</li>
 * </ol>
 * 실패 코드(pdf_failure_code)에는 본문·계좌 값을 넣지 않는다 — RENDER_FAILED·STORAGE_FAILED·NOT_STARTED 처럼 단계 이름만.
 * 로그도 초안 번호·예외 종류만 (NFR-COM-04).
 */
@Service
public class GenerateStrReportPdf {

    private static final Logger log = LoggerFactory.getLogger(GenerateStrReportPdf.class);

    static final String RENDER_FAILED = "RENDER_FAILED";
    static final String STORAGE_FAILED = "STORAGE_FAILED";
    static final String NOT_STARTED = "NOT_STARTED";

    private final StrReportRepository reports;
    private final ReportPdfRenderer renderer;
    private final ReportPdfStorage storage;
    private final TransactionTemplate tx;

    public GenerateStrReportPdf(StrReportRepository reports, ReportPdfRenderer renderer, ReportPdfStorage storage,
                                TransactionTemplate tx) {
        this.reports = reports;
        this.renderer = renderer;
        this.storage = storage;
        this.tx = tx;
    }

    /** @return 이번에 남긴 상태. PENDING 이 아니라 할 일이 없었으면 empty */
    public Optional<ReportPdfStatus> execute(long draftId) {
        Optional<Job> job = tx.execute(s -> reports.findByDraftId(draftId)
                .filter(r -> r.getPdfStatus() == ReportPdfStatus.PENDING)
                .map(r -> new Job(ReportPdfContent.submitted(r), ReportPdfStorage.keyOf(r.getReportNo(), r.getPdfAttempts() + 1))));
        if (job == null || job.isEmpty()) {
            return Optional.empty();
        }

        byte[] pdf;
        try {
            pdf = renderer.render(job.get().content());
        } catch (RuntimeException e) {
            log.error("확정본 PDF 그리기 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
            return fail(draftId, RENDER_FAILED);
        }
        String sha256 = StrPdfDigest.sha256(pdf);
        try {
            storage.put(job.get().key(), pdf);
        } catch (RuntimeException e) {
            log.error("확정본 PDF 보관 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
            return fail(draftId, STORAGE_FAILED);
        }

        String key = job.get().key();
        Boolean done = tx.execute(s -> pending(draftId).map(r -> {
            r.pdfReady(key, sha256, pdf.length, renderer.templateVersion(), Instant.now());
            reports.save(r);
            return true;
        }).orElse(false));
        if (Boolean.TRUE.equals(done)) {
            log.info("확정본 PDF 보관 draft_no={} bytes={}", DisplayNumber.draftNo(draftId), pdf.length);
            return Optional.of(ReportPdfStatus.READY);
        }
        return Optional.empty();
    }

    /**
     * PENDING 이면 FAILED(code) 로. 작업을 시작조차 못 했을 때(실행기 거절)도 부른다 — PENDING 이 남으면 P5 로도 못 살린다.
     * @return FAILED 로 바꿨으면 FAILED, 이미 PENDING 이 아니면 empty
     */
    public Optional<ReportPdfStatus> fail(long draftId, String code) {
        Boolean done = tx.execute(s -> pending(draftId).map(r -> {
            r.pdfFailed(code);
            reports.save(r);
            return true;
        }).orElse(false));
        return Boolean.TRUE.equals(done) ? Optional.of(ReportPdfStatus.FAILED) : Optional.empty();
    }

    private Optional<StrReport> pending(long draftId) {
        return reports.findByDraftId(draftId).filter(r -> r.getPdfStatus() == ReportPdfStatus.PENDING);
    }

    private record Job(ReportPdfContent content, String key) {}
}
