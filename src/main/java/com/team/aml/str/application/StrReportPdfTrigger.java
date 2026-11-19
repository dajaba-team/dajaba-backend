package com.team.aml.str.application;

import com.team.aml.shared.domain.DisplayNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 확정본 PDF 를 <b>커밋 뒤에</b> 뒤에서 만들게 건다 (서식 회의 10/30 결정 2 — "생성은 커밋 뒤, 실패해도 승인·제출은 그대로").
 * 모의 제출(P4)·다시 만들기(P5) 트랜잭션 안에서 부른다. 응답은 pdf_status PENDING 으로 바로 나가고, 화면은 S8 을 3초마다 다시 본다(계약서 S8).
 *
 * <ul>
 *   <li>롤백되면 아무것도 안 한다 — PENDING 이 커밋된 경우에만 만든다</li>
 *   <li>실행기는 Spring Boot 기본 applicationTaskExecutor (적재·케이스 생성 요청과 같음, RequestOpenCases)</li>
 *   <li>실행기가 거절하면 그 자리에서 FAILED(NOT_STARTED) — PENDING 이 남으면 P5(FAILED 만 받음)로도 못 살린다</li>
 *   <li>서버가 만드는 도중 죽으면 PENDING 이 남는다 — 오래된 PENDING 정리는 후속(str.md 확정 보고서 〔후속〕, #119 리뷰)</li>
 *   <li>aml.str.pdf.generate-after-report=false 면 걸지 않는다 — 상태(PENDING)를 그대로 보는 DB 테스트용. 운영 기본은 true</li>
 * </ul>
 */
@Component
public class StrReportPdfTrigger {

    private static final Logger log = LoggerFactory.getLogger(StrReportPdfTrigger.class);

    private final GenerateStrReportPdf generate;
    private final TaskExecutor executor;
    private final boolean enabled;

    public StrReportPdfTrigger(GenerateStrReportPdf generate,
                               @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                               @Value("${aml.str.pdf.generate-after-report:true}") boolean enabled) {
        this.generate = generate;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** 지금 트랜잭션이 커밋되면 draftId 의 확정본을 만든다. 트랜잭션 밖에서 부르면 바로 건다. */
    public void afterCommit(long draftId) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(draftId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(draftId);
            }
        });
    }

    private void submit(long draftId) {
        try {
            executor.execute(() -> run(draftId));
        } catch (RuntimeException e) {
            log.error("확정본 PDF 작업을 시작하지 못함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    e.getClass().getSimpleName());
            try {
                generate.fail(draftId, GenerateStrReportPdf.NOT_STARTED);
            } catch (RuntimeException again) {
                log.error("확정본 PDF 실패 기록도 못 함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                        again.getClass().getSimpleName());
            }
        }
    }

    /** 뒤 스레드 — 예외를 밖으로 던지지 않는다(실행기 로그만 남고 사라진다). GenerateStrReportPdf 가 FAILED 를 남긴다. */
    private void run(long draftId) {
        try {
            generate.execute(draftId);
        } catch (RuntimeException e) {
            log.error("확정본 PDF 작업 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
        }
    }
}
