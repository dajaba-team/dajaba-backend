package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 룰 스캔 (SD 명세 API 3, SCRUM-99). 동기 — 응답에 결과 건수. 트랜잭션은 여기 없고 start·scan 이 각자 잡는다.
 * start(시작 검사·RUNNING 커밋) → run(한 트랜잭션 스캔, 실패하면 FAILED 를 따로 남기고 다시 던짐).
 */
@Service
public class RunRuleScan {

    private final RuleScanRecorder recorder;
    private final RuleScanner scanner;
    private final BatchRunQuery batchRuns;
    private final CurrentUser currentUser;

    public RunRuleScan(RuleScanRecorder recorder, RuleScanner scanner, BatchRunQuery batchRuns,
                       CurrentUser currentUser) {
        this.recorder = recorder;
        this.scanner = scanner;
        this.batchRuns = batchRuns;
        this.currentUser = currentUser;
    }

    public RuleScanResult execute(LocalDate date) {
        long batchRunId = start(date, currentUser.require().userId());
        return run(batchRunId, date);
    }

    /** 409 DATASET_NOT_LOADED·DATE_OUT_OF_RANGE·BATCH_RUNNING·BATCH_ALREADY_DONE·NO_ACTIVE_RULES 면 아무것도 안 남긴다. */
    public long start(LocalDate date, long triggeredBy) {
        return recorder.start(date, triggeredBy);
    }

    public RuleScanResult run(long batchRunId, LocalDate date) {
        List<RuleAlertCount> counts;
        try {
            counts = scanner.scan(batchRunId, date);
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, failureMessage(e));
            throw e;
        }
        return new RuleScanResult(batchRuns.get(batchRunId), counts);
    }

    /** 업무 오류는 코드·메시지, 그 밖은 예외 이름만 — SQL·스택이 batch_runs 에 들어가지 않게. */
    static String failureMessage(RuntimeException e) {
        return e instanceof DomainException d ? d.code() + ": " + d.getMessage()
                : "룰 스캔 실패 (" + e.getClass().getSimpleName() + ")";
    }
}
