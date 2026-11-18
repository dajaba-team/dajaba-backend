package com.team.aml.cases.api;

import com.team.aml.cases.application.RunOpenCasesOnce;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobGiveUpHandler;
import com.team.aml.shared.messaging.JobQueues;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 케이스 생성 큐 {@code case.open-cases} 받는 곳 (SCRUM-276). 컨트롤러처럼 바깥에서 들어오는 입구라 api 계층.
 * RabbitMQ 를 켰을 때만 빈이 생긴다. 정상 반환 = ACK, 예외 = 재시도 후 DLQ (shared.messaging 공통 설정).
 * DLQ 로 가기 직전에는 {@link #onGiveUp}으로 batch_runs 행을 FAILED 로 바꿔 같은 날짜를 다시 요청할 수 있게 한다(#130 리뷰).
 */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class OpenCasesMessageListener implements JobGiveUpHandler<BatchRunMessage> {

    private final RunOpenCasesOnce runOnce;

    public OpenCasesMessageListener(RunOpenCasesOnce runOnce) {
        this.runOnce = runOnce;
    }

    @RabbitListener(queues = JobQueues.CASE_OPEN_CASES)
    public void onMessage(BatchRunMessage message) {
        runOnce.execute(message.batchRunId());
    }

    @Override
    public String queue() {
        return JobQueues.CASE_OPEN_CASES;
    }

    @Override
    public Class<BatchRunMessage> messageType() {
        return BatchRunMessage.class;
    }

    @Override
    public void onGiveUp(BatchRunMessage message) {
        runOnce.giveUp(message.batchRunId());
    }
}
