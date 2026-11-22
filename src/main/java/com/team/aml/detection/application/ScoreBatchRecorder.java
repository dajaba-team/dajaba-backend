package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

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
    private final ObjectMapper objectMapper;

    public ScoreBatchRecorder(BatchRunRepository batchRuns, AlertScoreRepository scores, ObjectMapper objectMapper) {
        this.batchRuns = batchRuns;
        this.scores = scores;
        this.objectMapper = objectMapper;
    }

    /** 409 RULE_SCAN_NOT_DONE·BATCH_RUNNING 이면 아무것도 남기지 않는다. 통과하면 RUNNING 을 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate date, long triggeredBy) {
        scores.lockScoreStart();
        return startLocked(date, triggeredBy);
    }

    /**
     * 배치 잇기(SCRUM-349)용 — 같은 시작 잠금 안에서 "since 뒤에 그 날짜 점수가 이미 시작됐나"를 먼저 본다. 시작됐으면(직접 실행이 그새
     * 시작해 끝났어도) 아무것도 남기지 않고 빈 값. 잠금 밖에서 보면 확인과 시작 사이에 직접 실행이 끝나 한 번 더 돌 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong startUnlessStartedSince(LocalDate date, long triggeredBy, Instant since) {
        scores.lockScoreStart();
        if (batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(BatchType.SCORE_REQUEST, date, since)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(startLocked(date, triggeredBy));
    }

    /** 시작 잠금을 쥔 채로만. */
    private long startLocked(LocalDate date, long triggeredBy) {
        if (!batchRuns.existsByBatchTypeAndBatchDateAndStatus(BatchType.RULE_SCAN, date, BatchStatus.SUCCEEDED)) {
            throw new RuleScanNotDoneException(date);
        }
        if (batchRuns.findFirstByBatchTypeAndBatchDateAndStatus(BatchType.SCORE_REQUEST, date, BatchStatus.RUNNING)
                .isPresent()) {
            throw new BatchRunningException(BatchType.SCORE_REQUEST, date);
        }
        return batchRuns.save(BatchRun.start(BatchType.SCORE_REQUEST, date, triggeredBy)).getId();
    }

    /**
     * 다시 보낼 실패가 남았으면 FAILED(건수 메시지) — 다시 누르면 남은 것만 보낸다. 아니면 SUCCEEDED.
     * 둘 다 건수는 summary 에 — 202 비동기라 응답 대신 상태 조회(B5)가 내려준다(SCRUM-349).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long batchRunId, ScoreBatchSummary summary) {
        BatchRun run = batchRuns.findById(batchRunId).orElseThrow();
        JsonNode json = objectMapper.valueToTree(ScoreSummaryJson.of(summary));
        if (summary.retryableFailed() > 0) {
            run.fail(summary.describe(), json);
        } else {
            run.succeed(summary.requested(), summary.scored(), json);
        }
    }

    /**
     * batch_runs.summary 모양 (SNAKE_CASE) — 예전 점수 응답(SD 명세 v0.3 §6)과 같은 이름이라 화면이 그대로 쓴다.
     * retryable = failed 중 다시 보내면 바뀔 수 있는 것(코드 NULL·COMPUTE_ERROR) — 화면 [점수 재처리] 건수(계약서 5.6 B3).
     */
    record ScoreSummaryJson(int requested, int scored, int failed, int retryable, Chunks chunks,
                            List<String> modelVersions) {
        record Chunks(int total, int failed) {
        }

        static ScoreSummaryJson of(ScoreBatchSummary s) {
            return new ScoreSummaryJson(s.requested(), s.scored(), s.failed(), s.retryableFailed(),
                    new Chunks(s.chunksTotal(), s.chunksFailed()), s.modelVersions());
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
