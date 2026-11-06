package com.team.aml.source.application;

import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 적재 요청 (SD 명세 API 1). RUNNING 을 커밋한 뒤 적재는 뒤에서 돌리고 바로 돌아온다 → 202.
 * 비동기 스레드엔 세션이 없어서 요청자 id 는 여기서 꺼내 넘긴다.
 */
@Service
public class RequestIbmDatasetLoad {

    private static final Logger log = LoggerFactory.getLogger(RequestIbmDatasetLoad.class);
    static final String NOT_STARTED_MESSAGE = "적재 작업을 시작하지 못함";

    private final LoadIbmDataset loadIbmDataset;
    private final DatasetLoadRecorder recorder;
    private final BatchRunQuery batchRuns;
    private final CurrentUser currentUser;
    private final TaskExecutor executor;

    /** Spring Boot 기본 작업 실행기. 적재는 한 번에 하나라 따로 만들지 않는다. */
    public RequestIbmDatasetLoad(LoadIbmDataset loadIbmDataset, DatasetLoadRecorder recorder, BatchRunQuery batchRuns,
                                 CurrentUser currentUser,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.loadIbmDataset = loadIbmDataset;
        this.recorder = recorder;
        this.batchRuns = batchRuns;
        this.currentUser = currentUser;
        this.executor = executor;
    }

    /**
     * 파일 없음·이미 적재·실행 중이면 여기서 409 를 던지고, 접수되면 RUNNING 상태의 배치를 돌려준다.
     * 응답은 작업을 넘기기 전에 읽는다 — 넘긴 뒤 읽으면 작은 데이터는 이미 끝나 SUCCEEDED 가 찍힐 수 있다.
     * 실행기가 작업을 거절하면 run() 이 안 돌아 RUNNING 이 남고 다음 적재가 막히므로, FAILED 로 기록하고 다시 던진다.
     */
    public BatchRunView execute() {
        long batchRunId = loadIbmDataset.start(currentUser.require().userId());
        BatchRunView accepted = batchRuns.get(batchRunId);
        try {
            executor.execute(() -> loadIbmDataset.run(batchRunId));
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, NOT_STARTED_MESSAGE + " (" + e.getClass().getSimpleName() + ")");
            log.error("적재 작업 제출 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            throw e;
        }
        return accepted;
    }
}
