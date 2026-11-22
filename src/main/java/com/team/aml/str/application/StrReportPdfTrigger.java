package com.team.aml.str.application;

import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrReportPdfMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


/**
 * 확정본 PDF 작업을 DB 커밋 뒤에 비동기로 제출한다.
 *
 * 모의 제출(P4)·다시 만들기(P5) 트랜잭션 안에서 호출한다.
 * 트랜잭션이 커밋된 뒤 작업을 제출하며, 트랜잭션 밖에서 호출하면 바로 제출한다.
 *
 * 먼저 {@code applicationTaskExecutor}에 작업을 넘겨 요청 스레드에서
 * RabbitMQ confirm을 기다리지 않도록 한다.
 * RabbitMQ가 켜져 있고 브로커가 발행을 확인하면
 * {@code str.pdf.generate} 큐에서 처리한다.
 * RabbitMQ가 꺼져 있거나 발행에 실패하면 기존 앱 내부 실행기로 처리한다.
 *
 * <ul>
 *   <li>트랜잭션이 롤백되면 아무 작업도 하지 않는다.</li>
 *   <li>실행기가 작업을 거절하면 그 자리에서 {@code FAILED(NOT_STARTED)}를 기록한다.</li>
 *   <li>PDF 생성 도중 서버가 종료되면 {@code PENDING}이 남는다. 10분 뒤 P5에서 복구한다.</li>
 *   <li>{@code aml.str.pdf.generate-after-report=false}이면 생성 작업을 제출하지 않는다.</li>
 *   <li>RabbitMQ 경로에서 재시도를 모두 소진하면 {@code QUEUE_GIVE_UP}으로 처리한다.</li>
 *   <li>같은 JVM 안에서 발생한 중복 실행은 {@link GenerateStrReportPdf}가 막는다.</li>
 * </ul>
 */

@Component
public class StrReportPdfTrigger {

    private static final Logger log = LoggerFactory.getLogger(StrReportPdfTrigger.class);

    private final GenerateStrReportPdf generate;
    private final JobPublisher publisher;
    private final TaskExecutor executor;
    private final boolean enabled;

    public StrReportPdfTrigger(GenerateStrReportPdf generate, JobPublisher publisher,
                               @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                               @Value("${aml.str.pdf.generate-after-report:true}") boolean enabled) {
        this.generate = generate;
        this.publisher = publisher;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** 지금 트랜잭션이 커밋되면 draftId 작업을 제출한다. 트랜잭션 밖에서 부르면 바로 제출한다. */
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

    /** 요청 스레드에서 RabbitMQ confirm을 기다리지 않도록 먼저 앱 실행기에 넘긴다. */
    private void submit(long draftId) {
        try {
            executor.execute(() -> enqueueOrRun(draftId));
        } catch (RuntimeException e) {
            log.error("확정본 PDF 작업을 시작하지 못함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    e.getClass().getSimpleName());
            failNotStarted(draftId);
        }
    }

    private void enqueueOrRun(long draftId) {
        if (publisher.publish(JobQueues.STR_PDF_GENERATE, new StrReportPdfMessage(draftId))) {
            log.info("확정본 PDF 작업을 큐에 넣음 draft_no={}", DisplayNumber.draftNo(draftId));
            return;
        }
        // RabbitMQ 미사용·연결 실패·confirm 실패 시 기존 동작을 유지한다.
        runLocally(draftId);
    }

    private void runLocally(long draftId) {
        try {
            generate.execute(draftId);
        } catch (RuntimeException e) {
            log.error("확정본 PDF 작업 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    e.getClass().getSimpleName());
        }
    }

    private void failNotStarted(long draftId) {
        try {
            generate.fail(draftId, GenerateStrReportPdf.NOT_STARTED);
        } catch (RuntimeException again) {
            log.error("확정본 PDF 실패 기록도 못 함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    again.getClass().getSimpleName());
        }
    }
}
