package com.team.aml.cases.api;

import com.team.aml.cases.application.OpenCasesAfterScore;
import com.team.aml.shared.messaging.InAppJob;
import com.team.aml.shared.messaging.JobQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 배치 잇기 {@code batch.open-cases} 의 앱 안 경로 (SCRUM-349) — RabbitMQ 가 꺼져 있거나 브로커가 받지 않았을 때.
 * {@link OpenCasesChainListener}(큐)와 같은 일을 앱 작업 실행기에서 한다. 그 뒤는 화면 요청과 같은 RequestOpenCases 경로.
 */
@Component
public class OpenCasesChainInApp {

    private static final Logger log = LoggerFactory.getLogger(OpenCasesChainInApp.class);

    private final OpenCasesAfterScore openCasesAfterScore;
    private final TaskExecutor executor;

    public OpenCasesChainInApp(OpenCasesAfterScore openCasesAfterScore,
                               @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.openCasesAfterScore = openCasesAfterScore;
        this.executor = executor;
    }

    @EventListener
    public void on(InAppJob job) {
        if (!JobQueues.BATCH_OPEN_CASES.equals(job.queue())) {
            return;
        }
        executor.execute(() -> {
            try {
                openCasesAfterScore.execute(job.batchRunId());
            } catch (RuntimeException e) {   // 큐면 재시도할 DB 오류 — 앱 안 경로는 재시도 없이 로그만(관리자가 직접 실행)
                log.error("배치 잇기 케이스 생성 실패 batch_run_id={} cause={}", job.batchRunId(),
                        e.getClass().getSimpleName());
            }
        });
    }
}
