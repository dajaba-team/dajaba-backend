package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.detection.domain.RuleScanNotDoneException;
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

import java.time.LocalDate;

/**
 * 점수 배치(SCORE_REQUEST)의 batch_runs 기록. 점수는 묶음마다 따로 커밋되므로 기록도 따로(REQUIRES_NEW) 커밋한다.
 * 성공한 날도 다시 돌린다(V012) — 다시 돌리면 남은 대상만 보낸다.
 */
@Service
public class ScoreBatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScoreBatchRecorder.class);
    static final String ABANDONED_MESSAGE = "앱 재시작으로 중단된 점수 배치";

    private final BatchRunRepository batchRuns;
    private final AlertScoreRepository scores;

    public ScoreBatchRecorder(BatchRunRepository batchRuns, AlertScoreRepository scores) {
        this.batchRuns = batchRuns;
        this.scores = scores;
    }

    /** 409 RULE_SCAN_NOT_DONE·BATCH_RUNNING 이면 아무것도 남기지 않는다. 통과하면 RUNNING 을 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate date, long triggeredBy) {
        scores.lockScoreStart();
        if (!batchRuns.existsByBatchTypeAndBatchDateAndStatus(BatchType.RULE_SCAN, date, BatchStatus.SUCCEEDED)) {
            throw new RuleScanNotDoneException(date);
        }
        if (batchRuns.findFirstByBatchTypeAndBatchDateAndStatus(BatchType.SCORE_REQUEST, date, BatchStatus.RUNNING)
                .isPresent()) {
            throw new BatchRunningException(BatchType.SCORE_REQUEST, date);
        }
        return batchRuns.save(BatchRun.start(BatchType.SCORE_REQUEST, date, triggeredBy)).getId();
    }

    /** 다시 보낼 실패가 남았으면 FAILED(건수 메시지) — 다시 누르면 남은 것만 보낸다. 아니면 SUCCEEDED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long batchRunId, ScoreBatchSummary summary) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        if (summary.retryableFailed() > 0) {
            run.fail(summary.describe());
        } else {
            run.succeed(summary.requested(), summary.scored());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long batchRunId, String message) {
        batchRuns.findById(batchRunId).orElseThrow().fail(message);
    }

    /** 단일 서버 전제 — RuleScanRecorder.failAbandonedScans 와 같은 이유. 남은 RUNNING 이 그 날짜를 막지 않게. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failAbandonedScoreBatches() {
        int cleaned = scores.failRunningScoreBatches(ABANDONED_MESSAGE);
        if (cleaned > 0) {
            log.warn("중단된 점수 배치 {}건을 FAILED로 정리", cleaned);
        }
    }
}
