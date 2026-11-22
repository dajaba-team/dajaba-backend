package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;

/**
 * 케이스 생성 배치(OPEN_CASES)의 batch_runs 기록. 케이스는 경보마다 따로 커밋되므로 기록도 따로(REQUIRES_NEW) 커밋한다.
 * 실패한 경보가 한 건이라도 있으면 FAILED(건수 메시지) — 그 날짜를 다시 돌리면 남은 대상만 처리한다.
 * 실패 없이 끝나면 SUCCEEDED. <b>성공한 날도 다시 돌린다</b>(회의 14 보충 실행, V014, SCRUM-238) — 대상은 아직 케이스가 없는
 * SCORED 경보뿐이라 새로 HIGH 가 된 경보만 케이스가 된다. 같은 날짜가 돌고 있으면 409 BATCH_RUNNING(시작 advisory lock 으로 줄 세움).
 */
@Service
class OpenCasesRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpenCasesRecorder.class);
    static final String ABANDONED_MESSAGE = "앱 재시작으로 중단된 케이스 생성";

    private final BatchRunRepository batchRuns;
    private final CaseRepository cases;

    OpenCasesRecorder(BatchRunRepository batchRuns, CaseRepository cases) {
        this.batchRuns = batchRuns;
        this.cases = cases;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate batchDate, long triggeredBy) {
        cases.lockOpenCasesStart();
        return startLocked(batchDate, triggeredBy);
    }

    /**
     * 배치 잇기(SCRUM-349)용 — 같은 시작 잠금 안에서 "since 뒤에 그 날짜 케이스 생성이 이미 시작됐나"를 먼저 본다.
     * 시작됐으면(직접 실행이 그새 시작해 끝났어도) 아무것도 남기지 않고 빈 값. 그 밖은 start 와 같다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong startUnlessStartedSince(LocalDate batchDate, long triggeredBy, Instant since) {
        cases.lockOpenCasesStart();
        if (batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(BatchType.OPEN_CASES, batchDate, since)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(startLocked(batchDate, triggeredBy));
    }

    /** 시작 잠금을 쥔 채로만. */
    private long startLocked(LocalDate batchDate, long triggeredBy) {
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

    /**
     * 앱이 뜰 때 남은 케이스 생성 RUNNING 을 FAILED 로 (SCRUM-246) — 적재·룰 스캔·점수 Recorder 와 같은 단일 서버 전제.
     * 시작 시각으로 거르지 않는다(남기면 앱 안 실행기 경로에선 영원히 RUNNING).
     * <b>지금 처리 중인 행은 건드리지 않는다</b>(#168 리뷰): RabbitMQ 리스너는 ApplicationReadyEvent 보다 먼저 떠서, 큐에 남은
     * case.open-cases 메시지를 받아 RunOpenCasesOnce 가 그 RUNNING 행을 이미 처리하고 있을 수 있다. 처리 중엔 실행 번호
     * advisory lock(tryLockOpenCasesRun)을 쥐고 있으니, 같은 잠금을 못 잡는 행은 건너뛴다 — 끝나면 그쪽이 결과를 남긴다.
     * 잠금은 이 트랜잭션이 끝날 때 풀리고, 그 뒤에 온 메시지는 행이 RUNNING 이 아니라 건너뛴다(giveUp 과 같은 방식).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failAbandonedOpenCases() {
        int cleaned = 0;
        for (BatchRun run : batchRuns.findByBatchTypeAndStatus(BatchType.OPEN_CASES, BatchStatus.RUNNING)) {
            if (!cases.tryLockOpenCasesRun(run.getId())) {
                log.info("케이스 생성 정리 건너뜀 batch_run_id={} 지금 처리 중", run.getId());
                continue;
            }
            run.fail(ABANDONED_MESSAGE);
            cleaned++;
        }
        if (cleaned > 0) {
            log.warn("중단된 케이스 생성 {}건을 FAILED로 정리", cleaned);
        }
    }
}
