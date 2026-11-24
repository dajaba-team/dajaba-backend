package com.team.aml.str.application;

import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrDraftMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 초안 생성 작업을 뒤로 넘긴다 (S1 비동기). 요청 기록(REQUESTED)이 <b>커밋된 뒤에</b> 부른다 — RequestStrDraft.submit 의
 * 요청 트랜잭션은 TransactionTemplate 이라 반환되면 이미 커밋돼 있다. 모양은 확정본 PDF(StrReportPdfTrigger)와 같다.
 *
 * <ul>
 *   <li>요청 스레드는 브로커 확인을 기다리지 않는다 — 먼저 applicationTaskExecutor 에 넘긴다</li>
 *   <li>RabbitMQ 가 켜져 있고 브로커가 받으면 {@code str.draft.generate} 큐에서, 꺼졌거나 보내기 실패면 앱 안에서 GenerateStrDraft</li>
 *   <li>실행기가 거절하면 그 자리에서 FAILED — REQUESTED 로 남으면 같은 케이스의 다음 요청이 409 DRAFT_IN_PROGRESS 를 받는다</li>
 *   <li>aml.str.draft.generate-after-request=false 면 넘기지 않는다 — 상태(REQUESTED)를 그대로 보는 DB 테스트용</li>
 * </ul>
 */
@Component
public class StrDraftTrigger {

    private static final Logger log = LoggerFactory.getLogger(StrDraftTrigger.class);

    static final String NOT_STARTED = "NOT_STARTED";

    private final GenerateStrDraft generate;
    private final JobPublisher publisher;
    private final TaskExecutor executor;
    private final boolean enabled;

    public StrDraftTrigger(GenerateStrDraft generate, JobPublisher publisher,
                           @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                           @Value("${aml.str.draft.generate-after-request:true}") boolean enabled) {
        this.generate = generate;
        this.publisher = publisher;
        this.executor = executor;
        this.enabled = enabled;
    }

    public void submit(long draftId) {
        if (!enabled) {
            return;
        }
        try {
            executor.execute(() -> enqueueOrRun(draftId));
        } catch (RuntimeException e) {
            log.error("초안 생성 작업을 시작하지 못함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    e.getClass().getSimpleName());
            failQuietly(draftId, NOT_STARTED);
        }
    }

    private void enqueueOrRun(long draftId) {
        if (publisher.publish(JobQueues.STR_DRAFT_GENERATE, new StrDraftMessage(draftId))) {
            log.info("초안 생성 작업을 큐에 넣음 draft_no={}", DisplayNumber.draftNo(draftId));
            return;
        }
        try {
            generate.execute(draftId);                    // 큐 꺼짐·브로커 죽음 → 앱 안에서 (실패 기록은 GenerateStrDraft 가)
        } catch (RuntimeException e) {
            log.error("초안 생성 작업 실패 draft_no={} cause={}", DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
        }
    }

    private void failQuietly(long draftId, String reason) {
        try {
            generate.fail(draftId, reason);
        } catch (RuntimeException again) {
            log.error("초안 요청 실패 기록도 못 함 draft_no={} cause={}", DisplayNumber.draftNo(draftId),
                    again.getClass().getSimpleName());
        }
    }
}
