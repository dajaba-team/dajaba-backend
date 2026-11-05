package com.team.aml.shared.batch;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 BATCH_RUN_NOT_FOUND */
public class BatchRunNotFoundException extends NotFoundException {
    public BatchRunNotFoundException(long batchRunId) {
        super("배치 실행 기록이 없습니다", Map.of("batch_run_id", batchRunId));
    }
}
