package com.team.aml.detection.api;

import com.team.aml.detection.application.ScoreAfterRuleScan;
import com.team.aml.shared.messaging.InAppJob;
import com.team.aml.shared.messaging.JobQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 배치 잇기 {@code batch.score} 의 앱 안 경로 (SCRUM-349) — RabbitMQ 가 꺼져 있거나 브로커가 받지 않았을 때.
 * {@link ScoreChainListener}(큐)와 같은 일을 앱 작업 실행기에서 한다. 보낸 쪽(룰 스캔 스레드)을 점수 시간만큼 붙잡지 않으려고 넘긴다.
 * 실행기가 거절하면 예외가 보낸 쪽(BatchChain)으로 가서 로그만 남는다 — 점수 행을 만들기 전이라 RUNNING 으로 남는 행이 없다.
 */
@Component
public class ScoreChainInApp {

    private static final Logger log = LoggerFactory.getLogger(ScoreChainInApp.class);

    private final ScoreAfterRuleScan scoreAfterRuleScan;
    private final TaskExecutor executor;

    public ScoreChainInApp(ScoreAfterRuleScan scoreAfterRuleScan,
                           @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.scoreAfterRuleScan = scoreAfterRuleScan;
        this.executor = executor;
    }

    @EventListener
    public void on(InAppJob job) {
        if (!JobQueues.BATCH_SCORE.equals(job.queue())) {
            return;
        }
        executor.execute(() -> {
            try {
                scoreAfterRuleScan.execute(job.batchRunId());
            } catch (RuntimeException e) {   // 큐면 재시도할 DB 오류 — 앱 안 경로는 재시도 없이 로그만(관리자가 직접 실행)
                log.error("배치 잇기 점수 실패 batch_run_id={} cause={}", job.batchRunId(), e.getClass().getSimpleName());
            }
        });
    }
}
