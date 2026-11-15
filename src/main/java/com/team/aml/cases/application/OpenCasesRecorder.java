package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
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
 * 실패 없이 끝나면 SUCCEEDED. <b>성공한 날도 다시 돌린다</b>(회의 14 보충 실행, V014, SCRUM-238) — 대상은 아직 케이스가 없는
 * SCORED 경보뿐이라 새로 HIGH 가 된 경보만 케이스가 된다. 같은 날짜가 돌고 있으면 409 BATCH_RUNNING(시작 advisory lock 으로 줄 세움).
 */
@Service
class OpenCasesRecorder {

    private final BatchRunRepository batchRuns;
    private final CaseRepository cases;

    OpenCasesRecorder(BatchRunRepository batchRuns, CaseRepository cases) {
        this.batchRuns = batchRuns;
        this.cases = cases;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate batchDate, long triggeredBy) {
        cases.lockOpenCasesStart();
        if (batchRuns.findFirstByBatchTypeAndBatchDateAndStatus(BatchType.OPEN_CASES, batchDate, BatchStatus.RUNNING)
                .isPresent()) {
            throw new BatchRunningException(BatchType.OPEN_CASES, batchDate);
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
