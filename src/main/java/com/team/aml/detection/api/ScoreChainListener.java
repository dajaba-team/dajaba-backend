package com.team.aml.detection.api;

import com.team.aml.detection.application.ScoreAfterRuleScan;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobQueues;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 배치 잇기 {@code batch.score} 받는 곳 (SCRUM-349) — 성공한 룰 스캔 뒤 같은 날짜 점수. 컨트롤러처럼 바깥에서 들어오는 입구라 api 계층.
 * RabbitMQ 를 켰을 때만 빈이 생긴다. 정상 반환 = ACK, 예외 = 재시도 후 DLQ(shared.messaging 공통 설정).
 * 포기 처리(JobGiveUpHandler)는 두지 않는다 — 예외는 점수 행을 만들기 전에만 나가서 RUNNING 으로 남는 행이 없다.
 */
@Component
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
public class ScoreChainListener {

    private final ScoreAfterRuleScan scoreAfterRuleScan;

    public ScoreChainListener(ScoreAfterRuleScan scoreAfterRuleScan) {
        this.scoreAfterRuleScan = scoreAfterRuleScan;
    }

    @RabbitListener(queues = JobQueues.BATCH_SCORE)
    public void onMessage(BatchRunMessage message) {
        scoreAfterRuleScan.execute(message.batchRunId());
    }
}
