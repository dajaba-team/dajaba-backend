package com.team.aml.source.application;

import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.source.domain.DatasetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 적재의 batch_runs 기록. 적재 트랜잭션과 따로 커밋해야 적재가 롤백돼도 실패 기록이 남는다 (SD 명세 §3 ①·③).
 */
@Service
public class DatasetLoadRecorder {

    private final BatchRunRepository batchRuns;
    private final DatasetRepository dataset;

    public DatasetLoadRecorder(BatchRunRepository batchRuns, DatasetRepository dataset) {
        this.batchRuns = batchRuns;
        this.dataset = dataset;
    }

    /** 실행 중 적재가 있거나 이미 적재된 DB면 거절하고, 아니면 RUNNING 행을 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate batchDate, long triggeredBy) {
        dataset.lockLoadStart();
        if (dataset.hasRunningLoad()) {
            throw new BatchRunningException(BatchType.LOAD_DATASET, batchDate);
        }
        if (dataset.hasTransactions()) {
            throw new BatchAlreadyDoneException(BatchType.LOAD_DATASET, batchDate);
        }
        return batchRuns.save(BatchRun.start(BatchType.LOAD_DATASET, batchDate, triggeredBy)).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long batchRunId, long rowsIn, long rowsOut) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        run.succeed(Math.toIntExact(rowsIn), Math.toIntExact(rowsOut));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long batchRunId, String message) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        run.fail(message);
    }
}
