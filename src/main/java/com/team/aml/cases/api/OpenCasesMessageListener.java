package com.team.aml.cases.api;

import com.team.aml.cases.application.RunQueuedOpenCases;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobQueues;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 케이스 생성 큐 {@code case.open-cases} 받는 곳 (SCRUM-276). 컨트롤러처럼 바깥에서 들어오는 입구라 api 계층.
 * RabbitMQ 를 켰을 때만 빈이 생긴다. 정상 반환 = ACK, 예외 = 재시도 후 DLQ (shared.messaging 공통 설정).
 */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class OpenCasesMessageListener {

    private final RunQueuedOpenCases runQueued;

    public OpenCasesMessageListener(RunQueuedOpenCases runQueued) {
        this.runQueued = runQueued;
    }

    @RabbitListener(queues = JobQueues.CASE_OPEN_CASES)
    public void onMessage(BatchRunMessage message) {
        runQueued.execute(message.batchRunId());
    }
}
