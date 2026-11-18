package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 큐로 받은 케이스 생성 작업 실행 (SCRUM-276). 메시지에는 batch_run_id 만 있고 날짜·상태는 batch_runs 에서 읽는다.
 * 실제 처리는 기존 OpenCasesFromAlerts.run — 경보 단위 트랜잭션·부분 실패 정책 그대로.
 *
 * <p><b>같은 메시지가 다시 와도 안전</b>: 행이 RUNNING 이 아니면(이미 끝남) 건너뛴다. RUNNING 인 채로 다시 오면(처리 중 앱이 죽어
 * 브로커가 재전달) 다시 돌리는데, 경보당 케이스 1건(alert_id UNIQUE)이라 중복 케이스는 생기지 않고 남은 경보만 처리된다.
 * 없는 id·다른 배치 유형은 다시 보내도 소용없으므로 경고만 남기고 버린다(ACK).
 */
@Service
public class RunQueuedOpenCases {

    private static final Logger log = LoggerFactory.getLogger(RunQueuedOpenCases.class);

    /** 처리 결과 — 로그·테스트용. */
    public enum Outcome { RAN, SKIPPED_NOT_FOUND, SKIPPED_WRONG_TYPE, SKIPPED_NOT_RUNNING }

    private final BatchRunRepository batchRuns;
    private final OpenCasesFromAlerts openCases;

    public RunQueuedOpenCases(BatchRunRepository batchRuns, OpenCasesFromAlerts openCases) {
        this.batchRuns = batchRuns;
        this.openCases = openCases;
    }

    /** DB 조회가 실패하면 예외가 그대로 나가 리스너 재시도 → DLQ. run() 자체는 예외를 던지지 않는다. */
    public Outcome execute(long batchRunId) {
        Optional<BatchRun> found = batchRuns.findById(batchRunId);
        if (found.isEmpty()) {
            log.warn("케이스 생성 메시지 버림 batch_run_id={} 행 없음", batchRunId);
            return Outcome.SKIPPED_NOT_FOUND;
        }
        BatchRun run = found.get();
        if (run.getBatchType() != BatchType.OPEN_CASES) {
            log.warn("케이스 생성 메시지 버림 batch_run_id={} batch_type={}", batchRunId, run.getBatchType());
            return Outcome.SKIPPED_WRONG_TYPE;
        }
        if (run.getStatus() != BatchStatus.RUNNING) {
            log.info("케이스 생성 메시지 건너뜀 batch_run_id={} 이미 {}", batchRunId, run.getStatus());
            return Outcome.SKIPPED_NOT_RUNNING;
        }
        openCases.run(batchRunId, run.getBatchDate());
        return Outcome.RAN;
    }
}
