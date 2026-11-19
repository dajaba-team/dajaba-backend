package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 케이스 생성 배치 한 건(batch_run_id)을 <b>한 번에 한 곳에서만</b> 돌린다 (SCRUM-276, #130 리뷰).
 * 큐 리스너(OpenCasesMessageListener)와 앱 안 실행기(RequestOpenCases 의 fail-open 경로)가 둘 다 이걸 부른다.
 * 메시지에는 batch_run_id 만 있고 날짜·상태는 batch_runs 에서 읽는다. 실제 처리는 기존 OpenCasesFromAlerts.run —
 * 경보 단위 트랜잭션·부분 실패 정책 그대로.
 *
 * <p><b>왜 잠그나</b>: 브로커 확인(confirm)이 늦으면 보낸 쪽은 "못 보냄"으로 보고 앱 안에서 돌리는데, 브로커는 실제로 받아서
 * 리스너도 돌 수 있다. 케이스 중복은 alert_id UNIQUE 로 막히지만, 두 실행이 각자 finish 를 불러 batch_runs 건수가 나중에
 * 끝난 쪽 값으로 덮인다. 그래서 실행 전에 batch_run_id 별 advisory lock 을 <b>기다리지 않고</b> 잡고, 못 잡으면 건너뛴다.
 * 잠금은 이 메서드의 트랜잭션이 끝날 때(= run 이 끝난 뒤) 풀린다. run 안의 기록·경보 처리는 이 트랜잭션에 끼지 않는다
 * (OpenCasesFromAlerts.run 이 NOT_SUPPORTED).
 *
 * <p><b>같은 메시지가 다시 와도 안전</b>: 잠근 <i>뒤에</i> 상태를 읽으므로, 다른 실행이 막 끝냈으면 RUNNING 이 아니라 건너뛴다.
 * RUNNING 인 채로 다시 오면(처리 중 앱이 죽어 브로커가 재전달 — 연결이 끊겨 잠금도 풀림) 다시 돌리는데, 경보당 케이스 1건이라
 * 중복 케이스는 생기지 않고 남은 경보만 처리된다. 없는 id·다른 배치 유형은 다시 보내도 소용없으므로 경고만 남기고 버린다(ACK).
 */
@Service
public class RunOpenCasesOnce {

    private static final Logger log = LoggerFactory.getLogger(RunOpenCasesOnce.class);
    static final String GAVE_UP_MESSAGE = "케이스 생성 큐 처리를 재시도 끝에 포기함 — 같은 날짜로 다시 요청하세요";

    /** 처리 결과 — 로그·테스트용. */
    public enum Outcome { RAN, MARKED_FAILED, SKIPPED_NOT_FOUND, SKIPPED_WRONG_TYPE, SKIPPED_NOT_RUNNING, SKIPPED_CLAIMED }

    private final BatchRunRepository batchRuns;
    private final CaseRepository cases;
    private final OpenCasesFromAlerts openCases;
    private final OpenCasesRecorder recorder;

    public RunOpenCasesOnce(BatchRunRepository batchRuns, CaseRepository cases, OpenCasesFromAlerts openCases,
                            OpenCasesRecorder recorder) {
        this.batchRuns = batchRuns;
        this.cases = cases;
        this.openCases = openCases;
        this.recorder = recorder;
    }

    /**
     * 트랜잭션은 잠금을 쥐고 있기 위한 것 — 이 안에서 쓰는 행은 없다(기록은 OpenCasesRecorder 가 따로 커밋).
     * DB 조회·잠금이 실패하면 예외가 그대로 나가 리스너 재시도 → 그래도 안 되면 {@link #giveUp}. run() 자체는 예외를 던지지 않는다.
     */
    @Transactional
    public Outcome execute(long batchRunId) {
        if (!cases.tryLockOpenCasesRun(batchRunId)) {
            log.info("케이스 생성 건너뜀 batch_run_id={} 다른 곳에서 실행 중", batchRunId);
            return Outcome.SKIPPED_CLAIMED;
        }
        Optional<BatchRun> found = runningOpenCases(batchRunId);
        if (found.isEmpty()) {
            return skipReason(batchRunId);
        }
        openCases.run(batchRunId, found.get().getBatchDate());
        return Outcome.RAN;
    }

    /**
     * 큐 재시도를 다 써서 메시지가 DLQ 로 가기 직전(shared.messaging 의 JobMessageRecoverer)에 부른다.
     * 행이 RUNNING 으로 남으면 같은 날짜 재요청이 409 BATCH_RUNNING 으로 막히므로 FAILED 로 바꿔 다시 요청할 수 있게 한다.
     * 다른 곳에서 실행 중(잠금을 못 잡음)이면 그쪽이 결과를 남기므로 건드리지 않는다.
     * DB 가 계속 안 되면 이것도 실패한다 — 그때는 §14 복구 절차(손으로 FAILED).
     */
    @Transactional
    public Outcome giveUp(long batchRunId) {
        if (!cases.tryLockOpenCasesRun(batchRunId)) {
            log.info("케이스 생성 포기 처리 건너뜀 batch_run_id={} 다른 곳에서 실행 중", batchRunId);
            return Outcome.SKIPPED_CLAIMED;
        }
        if (runningOpenCases(batchRunId).isEmpty()) {
            return skipReason(batchRunId);
        }
        recorder.fail(batchRunId, GAVE_UP_MESSAGE);
        log.warn("케이스 생성 큐 재시도 소진 batch_run_id={} → FAILED", batchRunId);
        return Outcome.MARKED_FAILED;
    }

    private Optional<BatchRun> runningOpenCases(long batchRunId) {
        return batchRuns.findById(batchRunId)
                .filter(r -> r.getBatchType() == BatchType.OPEN_CASES && r.getStatus() == BatchStatus.RUNNING);
    }

    private Outcome skipReason(long batchRunId) {
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
        log.info("케이스 생성 메시지 건너뜀 batch_run_id={} 이미 {}", batchRunId, run.getStatus());
        return Outcome.SKIPPED_NOT_RUNNING;
    }
}
