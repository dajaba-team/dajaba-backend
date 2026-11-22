package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 룰 스캔 (SD 명세 API 3, SCRUM-99). 트랜잭션은 여기 없고 start·scan 이 각자 잡는다.
 * start(시작 검사·RUNNING 커밋) → run(한 트랜잭션 스캔, 실패하면 FAILED 를 따로 남기고 다시 던짐).
 * 화면 요청(execute)은 202 — 적재(RequestIbmDatasetLoad)와 같은 모양으로 run 을 앱 실행기에 넘긴다(SCRUM-349).
 * 결과(룰별 경보 수)는 batch_runs.summary 에 남아 상태 조회(B5)가 내려준다.
 * 성공하면 배치 잇기(BatchChain)가 켜져 있을 때 같은 날짜 점수로 이어진다(SCRUM-349).
 */
@Service
public class RunRuleScan {

    private static final Logger log = LoggerFactory.getLogger(RunRuleScan.class);
    static final String NOT_STARTED_MESSAGE = "룰 스캔 작업을 시작하지 못함";

    private final RuleScanRecorder recorder;
    private final RuleScanner scanner;
    private final BatchRunQuery batchRuns;
    private final CurrentUser currentUser;
    private final BatchChain chain;
    private final TaskExecutor executor;

    /** Spring Boot 기본 작업 실행기 — 적재·케이스 생성과 같이 쓴다. */
    public RunRuleScan(RuleScanRecorder recorder, RuleScanner scanner, BatchRunQuery batchRuns,
                       CurrentUser currentUser, BatchChain chain,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.recorder = recorder;
        this.scanner = scanner;
        this.batchRuns = batchRuns;
        this.currentUser = currentUser;
        this.chain = chain;
        this.executor = executor;
    }

    /**
     * 202 (SCRUM-349). 409 는 start 가 바로 던지고 아무것도 남기지 않는다. 통과하면 RUNNING 을 커밋한 뒤 스캔은 앱 실행기로.
     * 응답은 넘기기 전에 읽는다 — 넘긴 뒤 읽으면 작은 날짜는 이미 끝나 SUCCEEDED 가 찍힐 수 있다.
     * 실행기가 작업을 거절하면 run() 이 안 돌아 RUNNING 이 남고 그 날짜가 막히므로, FAILED 로 기록하고 다시 던진다.
     */
    public BatchRunView execute(LocalDate date) {
        long batchRunId = start(date, currentUser.require().userId());
        BatchRunView accepted = batchRuns.get(batchRunId);
        try {
            executor.execute(() -> runInApp(batchRunId, date));
        } catch (RuntimeException e) {
            recorder.fail(batchRunId, NOT_STARTED_MESSAGE + " (" + e.getClass().getSimpleName() + ")");
            log.error("룰 스캔 작업 제출 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
            throw e;
        }
        return accepted;
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
        chain.afterRuleScan(batchRunId);   // 켜져 있으면 같은 날짜 점수로 이어짐
        return new RuleScanResult(batchRuns.get(batchRunId), counts);
    }

    /** 앱 실행기 경로 — run() 이 FAILED 를 남기고 다시 던진 예외는 로그만 남긴다(실행기 스레드 밖으로 내보내지 않음). */
    private void runInApp(long batchRunId, LocalDate date) {
        try {
            run(batchRunId, date);
        } catch (RuntimeException e) {
            log.error("룰 스캔 실패 batch_run_id={} cause={}", batchRunId, e.getClass().getSimpleName());
        }
    }

    /** 업무 오류는 코드·메시지, 그 밖은 예외 이름만 — SQL·스택이 batch_runs 에 들어가지 않게. */
    static String failureMessage(RuntimeException e) {
        return e instanceof DomainException d ? d.code() + ": " + d.getMessage()
                : "룰 스캔 실패 (" + e.getClass().getSimpleName() + ")";
    }
}
