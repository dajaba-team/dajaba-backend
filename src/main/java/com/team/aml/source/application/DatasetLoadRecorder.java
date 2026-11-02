package com.team.aml.source.application;

import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.source.domain.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 적재의 batch_runs 기록 (SD 명세 §3 ①·③).
 * 성공은 적재 트랜잭션 안에서 기록해 데이터와 함께 커밋되고, 실패는 따로 커밋해 롤백돼도 남는다.
 */
@Service
public class DatasetLoadRecorder {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoadRecorder.class);
    static final String ABANDONED_MESSAGE = "앱 재시작으로 중단된 적재";

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

    /** 적재 트랜잭션 안에서만 부른다. 데이터는 들어갔는데 상태가 RUNNING·FAILED로 남는 일을 막는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeed(long batchRunId, long rowsIn, long rowsOut) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        run.succeed(Math.toIntExact(rowsIn), Math.toIntExact(rowsOut));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long batchRunId, String message) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        run.fail(message);
    }

    /**
     * 적재는 이 앱 안에서만 돌고 백엔드는 한 대라, 기동 직후 남은 RUNNING 적재는 중간에 죽은 작업이다.
     * 데이터는 롤백됐으므로 FAILED로 바꿔 다음 적재를 막지 않게 한다. 다른 배치의 정리는 shared 공통 작업이다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failAbandonedLoads() {
        int cleaned = dataset.failRunningLoads(ABANDONED_MESSAGE);
        if (cleaned > 0) {
            log.warn("중단된 적재 {}건을 FAILED로 정리", cleaned);
        }
    }
}
