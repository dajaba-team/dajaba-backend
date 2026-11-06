package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
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
 */
@Service
public class RequestOpenCases {

    private static final Logger log = LoggerFactory.getLogger(RequestOpenCases.class);
    static final String NOT_STARTED_MESSAGE = "케이스 생성 작업을 시작하지 못함";

    /** 202 응답 (계약서 B4). 접수 직후라 상태는 항상 RUNNING. */
    public record Accepted(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status) {
    }

    private final OpenCasesFromAlerts openCases;
    private final OpenCasesRecorder recorder;
    private final CurrentUser currentUser;
    private final TaskExecutor executor;

    /** Spring Boot 기본 작업 실행기 — 적재와 같이 쓴다. */
    public RequestOpenCases(OpenCasesFromAlerts openCases, OpenCasesRecorder recorder, CurrentUser currentUser,
                            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.openCases = openCases;
        this.recorder = recorder;
        this.currentUser = currentUser;
        this.executor = executor;
    }

    /**
     * 정책 없음(POLICY_NOT_CONFIGURED)·실행 중(409 BATCH_RUNNING)·이미 성공(409 BATCH_ALREADY_DONE)이면 batch_runs 행 없이 거절.
     * 실행기가 작업을 거절하면 run() 이 안 돌아 RUNNING 이 남고 그 날짜가 막히므로 FAILED 로 기록하고 다시 던진다.
     */
    public Accepted execute(LocalDate batchDate) {
        long batchRunId = openCases.start(batchDate, currentUser.require().userId());
        try {
            executor.execute(() -> openCases.run(batchRunId, batchDate));
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, NOT_STARTED_MESSAGE + " (" + e.getClass().getSimpleName() + ")");
            log.error("케이스 생성 작업 제출 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            throw e;
        }
        return new Accepted(batchRunId, BatchType.OPEN_CASES, batchDate, BatchStatus.RUNNING);
    }
}
