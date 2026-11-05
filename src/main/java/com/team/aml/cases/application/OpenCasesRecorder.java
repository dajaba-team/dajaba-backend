package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 케이스 생성 배치(OPEN_CASES)의 batch_runs 기록. 케이스는 경보마다 따로 커밋되므로 기록도 따로(REQUIRES_NEW) 커밋한다.
 * 실패한 경보가 한 건이라도 있으면 FAILED(건수 메시지) — 그 날짜를 다시 돌리면 남은 대상만 처리한다.
 * 실패 없이 끝나면 SUCCEEDED. 성공한 날 재실행은 V004 batch_runs_succeeded_uq 때문에 지금은 409 BATCH_ALREADY_DONE
 * (성공한 날의 보충 실행은 안건 21 에서 인덱스를 좁힐 때 연다).
 */
@Service
class OpenCasesRecorder {

    private final BatchRunRepository batchRuns;

    OpenCasesRecorder(BatchRunRepository batchRuns) {
        this.batchRuns = batchRuns;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate batchDate, long triggeredBy) {
        if (batchRuns.findFirstByBatchTypeAndBatchDateAndStatus(BatchType.OPEN_CASES, batchDate, BatchStatus.RUNNING)
                .isPresent()) {
            throw new BatchRunningException(BatchType.OPEN_CASES, batchDate);
        }
        if (batchRuns.existsByBatchTypeAndBatchDateAndStatus(BatchType.OPEN_CASES, batchDate, BatchStatus.SUCCEEDED)) {
            throw new BatchAlreadyDoneException(BatchType.OPEN_CASES, batchDate);
        }
        return batchRuns.save(BatchRun.start(BatchType.OPEN_CASES, batchDate, triggeredBy)).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long batchRunId, OpenCasesSummary summary) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        if (summary.hasFailures()) {
            run.fail(summary.describe());
        } else {
            run.succeed(summary.candidates(), summary.opened());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long batchRunId, String message) {
        batchRuns.findById(batchRunId).orElseThrow().fail(message);
    }
}
