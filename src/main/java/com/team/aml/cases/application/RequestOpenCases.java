package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 케이스 생성 배치 요청 (CASES 명세 API-14, 계약서 B4 — 202 비동기, 11/4 준혁 제안·지윤 동의).
 * RUNNING 을 커밋한 뒤 경보 처리는 뒤에서 돌리고 바로 돌아온다. 결과는 GET /admin/batches/{batch_run_id}(SCRUM-70).
 * 적재 요청(RequestIbmDatasetLoad, #56)과 같은 모양 — 비동기 스레드엔 세션이 없어 요청자 id 는 여기서 꺼내 넘긴다.
 * 케이스·이력의 작업자는 SYSTEM(OpenCaseForAlert), batch_runs.triggered_by 는 요청한 ADMIN.
 *
 * <p>RabbitMQ 를 켜면(SCRUM-276) {@code case.open-cases} 큐에 batch_run_id 만 보내고 RunOpenCasesOnce 가 받아서 돌린다.
 * 꺼져 있거나 브로커가 받지 않으면(JobPublisher false) 지금처럼 이 앱의 작업 실행기로 돌린다(fail-open).
 * 두 경로 모두 RunOpenCasesOnce 를 거친다 — 확인(confirm)이 늦어 false 였는데 브로커가 실제로는 받았어도
 * 리스너와 실행기 중 먼저 잡은 쪽만 돌고 다른 쪽은 건너뛴다(#130 리뷰).
 */
@Service
public class RequestOpenCases {

    private static final Logger log = LoggerFactory.getLogger(RequestOpenCases.class);
    static final String NOT_STARTED_MESSAGE = "케이스 생성 작업을 시작하지 못함";

    /** 202 응답 (계약서 B4). 접수 직후라 상태는 항상 RUNNING. */
    public record Accepted(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status) {
    }

    private final OpenCasesFromAlerts openCases;
    private final RunOpenCasesOnce runOnce;
    private final OpenCasesRecorder recorder;
    private final CurrentUser currentUser;
    private final TaskExecutor executor;
    private final JobPublisher publisher;

    /** Spring Boot 기본 작업 실행기 — 적재와 같이 쓴다. */
    public RequestOpenCases(OpenCasesFromAlerts openCases, RunOpenCasesOnce runOnce, OpenCasesRecorder recorder,
                            CurrentUser currentUser, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                            JobPublisher publisher) {
        this.openCases = openCases;
        this.runOnce = runOnce;
        this.recorder = recorder;
        this.currentUser = currentUser;
        this.executor = executor;
        this.publisher = publisher;
    }

    /**
     * 정책 없음(POLICY_NOT_CONFIGURED)·실행 중(409 BATCH_RUNNING)이면 batch_runs 행 없이 거절. 성공한 날도 다시 돌린다(보충 실행, SCRUM-238).
     * 실행기가 작업을 거절하면 run() 이 안 돌아 RUNNING 이 남고 그 날짜가 막히므로 FAILED 로 기록하고 다시 던진다.
     */
    public Accepted execute(LocalDate batchDate) {
        long batchRunId = openCases.start(batchDate, currentUser.require().userId());
        if (publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(batchRunId))) {
            log.info("케이스 생성 큐에 넣음 batch_run_id={}", batchRunId);
            return new Accepted(batchRunId, BatchType.OPEN_CASES, batchDate, BatchStatus.RUNNING);
        }
        try {
            executor.execute(() -> runInApp(batchRunId));
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, NOT_STARTED_MESSAGE + " (" + e.getClass().getSimpleName() + ")");
            log.error("케이스 생성 작업 제출 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            throw e;
        }
        return new Accepted(batchRunId, BatchType.OPEN_CASES, batchDate, BatchStatus.RUNNING);
    }

    /**
     * 앱 안 실행기 경로. run() 은 예외를 안 던지지만 그 앞의 잠금·조회(DB)는 실패할 수 있다 — 그러면 RUNNING 이 남아
     * 그 날짜가 막히므로 포기 처리(FAILED)를 한 번 해 본다. 그것도 안 되면 로그만(§13 복구 절차).
     */
    private void runInApp(long batchRunId) {
        try {
            runOnce.execute(batchRunId);
        } catch (RuntimeException e) {
            log.error("케이스 생성 실행 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            try {
                runOnce.giveUp(batchRunId);
            } catch (RuntimeException again) {
                log.error("케이스 생성 포기 처리도 실패 batch_run_id={} cause={}", batchRunId, again.getClass().getSimpleName());
            }
        }
    }
}
