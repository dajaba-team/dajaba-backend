package com.team.aml.str.api;

import com.team.aml.shared.messaging.JobGiveUpHandler;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrDraftMessage;
import com.team.aml.str.application.GenerateStrDraft;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 에서 STR 초안 생성 작업을 받는다(S1 비동기). 정상 반환은 ACK, 예외는 공통 재시도(1s → 2s, 3번)·DLQ 정책(§14).
 * GenerateStrDraft 가 AI 실패는 FAILED 로 남기고 예외를 올리므로, 재시도 때는 REQUESTED 가 아니라 건너뛴다(AI 를 다시 부르지 않음).
 * AI 가 같은 초안을 아직 만드는 중(DRAFT_IN_PROGRESS)일 때만 실제로 다시 시도한다 — 재시도가 약 3초라 AI(최대 90초)가 끝나기 전에
 * 보통 다 쓰고 onGiveUp 으로 FAILED 가 된다(앱이 AI 호출 도중 죽은 드문 경우, 조사역이 다시 요청. #183 리뷰).
 */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class StrDraftMessageListener implements JobGiveUpHandler<StrDraftMessage> {

    static final String QUEUE_GIVE_UP = "QUEUE_GIVE_UP";

    private final GenerateStrDraft generate;

    public StrDraftMessageListener(GenerateStrDraft generate) {
        this.generate = generate;
    }

    @RabbitListener(queues = JobQueues.STR_DRAFT_GENERATE)
    public void onMessage(StrDraftMessage message) {
        generate.execute(message.draftId());
    }

    @Override
    public String queue() {
        return JobQueues.STR_DRAFT_GENERATE;
    }

    @Override
    public Class<StrDraftMessage> messageType() {
        return StrDraftMessage.class;
    }

    /** 재시도를 다 쓰면 REQUESTED 를 FAILED 로 — 남겨 두면 같은 케이스의 다음 S1 이 오래된 요청 기준까지 409 를 받는다. */
    @Override
    public void onGiveUp(StrDraftMessage message) {
        generate.fail(message.draftId(), QUEUE_GIVE_UP);
    }
}
