package com.team.aml.cases.api;

import com.team.aml.cases.application.OpenCasesAfterScore;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobQueues;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 배치 잇기 {@code batch.open-cases} 받는 곳 (SCRUM-349) — 성공한 점수 뒤 같은 날짜 케이스 생성 요청.
 * 실제 경보 처리는 기존 case.open-cases 큐(OpenCasesMessageListener)가 한다. RabbitMQ 를 켰을 때만 빈이 생긴다.
 * 포기 처리(JobGiveUpHandler)는 두지 않는다 — 케이스 생성 행을 만든 뒤의 실패는 RequestOpenCases·RunOpenCasesOnce 가 기록한다.
 */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class OpenCasesChainListener {

    private final OpenCasesAfterScore openCasesAfterScore;

    public OpenCasesChainListener(OpenCasesAfterScore openCasesAfterScore) {
        this.openCasesAfterScore = openCasesAfterScore;
    }

    @RabbitListener(queues = JobQueues.BATCH_OPEN_CASES)
    public void onMessage(BatchRunMessage message) {
        openCasesAfterScore.execute(message.batchRunId());
    }
}
