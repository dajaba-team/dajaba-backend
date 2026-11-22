package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 배치 잇기 2단계 (SCRUM-349) — 성공한 룰 스캔(batch_run_id)의 날짜로 점수를 돌린다. 요청자는 룰 스캔을 요청한 사람(triggered_by).
 * <ul>
 *   <li><b>같은 메시지가 다시 와도 한 번만</b>: 룰 스캔이 끝난 뒤 그 날짜 점수가 이미 시작됐으면(연쇄든 직접이든) 건너뛴다.
 *       이 확인은 점수 시작 잠금 안에서 한다 — 밖에서 보면 확인과 시작 사이에 직접 실행이 끝나 한 번 더 돌 수 있다.</li>
 *   <li>시작 거절(409 BATCH_RUNNING 등)은 건너뛴다. 동시 실행은 점수의 시작 규칙(advisory lock + RUNNING 확인)이 그대로 막는다.</li>
 *   <li>시작 뒤 실패는 RequestScores 가 FAILED 를 남기므로 로그만(ACK) — 다시 보내도 위 규칙으로 건너뛰니 큐 재시도로는 고칠 게 없다.
 *       다만 DB 가 끊겨 그 FAILED 기록까지 실패하면 행이 RUNNING 으로 남아 그 날짜 점수가 409 로 막히므로, 여기서 짧은 간격
 *       (1·2·4초)으로 FAILED 기록을 다시 해 본다. 그래도 안 되면 에러 로그 — 03_shared §14 손 정리(또는 앱 재시작 정리).</li>
 *   <li>행을 만들기 전 DB 오류만 예외로 내보내 큐가 재시도한다(그때는 남는 RUNNING 행이 없음).</li>
 * </ul>
 */
@Service
public class ScoreAfterRuleScan {

    private static final Logger log = LoggerFactory.getLogger(ScoreAfterRuleScan.class);
    static final String STUCK_MESSAGE = "배치 잇기 점수 실패 — 실패 기록을 다시 남김. 같은 날짜로 다시 실행하세요";
    private static final List<Duration> RECORD_RETRY = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4));

    /** 처리 결과 — 로그·테스트용. */
    public enum Outcome { RAN, RUN_FAILED, SKIPPED_NOT_FOUND, SKIPPED_NOT_SUCCEEDED, SKIPPED_ALREADY_STARTED, SKIPPED_REJECTED }

    /** 테스트에서 기다림을 없애려고 뺀 것. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final BatchRunRepository batchRuns;
    private final RequestScores scores;
    private final ScoreBatchRecorder recorder;
    private final Sleeper sleeper;

    @Autowired
    public ScoreAfterRuleScan(BatchRunRepository batchRuns, RequestScores scores, ScoreBatchRecorder recorder) {
        this(batchRuns, scores, recorder, d -> Thread.sleep(d.toMillis()));
    }

    ScoreAfterRuleScan(BatchRunRepository batchRuns, RequestScores scores, ScoreBatchRecorder recorder, Sleeper sleeper) {
        this.batchRuns = batchRuns;
        this.scores = scores;
        this.recorder = recorder;
        this.sleeper = sleeper;
    }

    public Outcome execute(long ruleScanRunId) {
        Optional<BatchRun> found = batchRuns.findById(ruleScanRunId)
                .filter(r -> r.getBatchType() == BatchType.RULE_SCAN);
        if (found.isEmpty()) {
            log.warn("배치 잇기 메시지 버림 batch_run_id={} 룰 스캔 행 없음", ruleScanRunId);
            return Outcome.SKIPPED_NOT_FOUND;
        }
        BatchRun scan = found.get();
        if (scan.getStatus() != BatchStatus.SUCCEEDED) {
            log.info("배치 잇기 건너뜀 batch_run_id={} 룰 스캔 {}", ruleScanRunId, scan.getStatus());
            return Outcome.SKIPPED_NOT_SUCCEEDED;
        }
        LocalDate date = scan.getBatchDate();
        OptionalLong started;
        try {
            started = scores.startUnlessStartedSince(date, scan.getTriggeredBy(), endOf(scan));
        } catch (DomainException e) {
            log.info("배치 잇기 건너뜀 batch_run_id={} 점수 시작 거절 code={}", ruleScanRunId, e.code());
            return Outcome.SKIPPED_REJECTED;
        }
        if (started.isEmpty()) {
            log.info("배치 잇기 건너뜀 batch_run_id={} 그 뒤 점수가 이미 시작됨", ruleScanRunId);
            return Outcome.SKIPPED_ALREADY_STARTED;
        }
        long scoreRunId = started.getAsLong();
        try {
            scores.run(scoreRunId, date);
        } catch (RuntimeException e) {
            log.warn("배치 잇기 점수 실패 batch_run_id={} cause={}", scoreRunId, e.getClass().getSimpleName());
            failIfStillRunning(scoreRunId);
            return Outcome.RUN_FAILED;
        }
        return Outcome.RAN;
    }

    /** RequestScores 의 FAILED 기록까지 실패해 RUNNING 으로 남았으면 다시 FAILED 로. 이미 끝 상태면 아무것도 안 한다. */
    void failIfStillRunning(long scoreRunId) {
        for (int attempt = 0; ; attempt++) {
            try {
                Optional<BatchRun> run = batchRuns.findById(scoreRunId);
                if (run.isEmpty() || run.get().getStatus() != BatchStatus.RUNNING) {
                    return;
                }
                recorder.fail(scoreRunId, STUCK_MESSAGE);
                log.warn("배치 잇기 점수 행을 FAILED 로 정리 batch_run_id={}", scoreRunId);
                return;
            } catch (RuntimeException e) {
                if (attempt >= RECORD_RETRY.size()) {
                    log.error("배치 잇기 점수 실패 기록 못 함 batch_run_id={} — RUNNING 으로 남음, 03_shared §14 손 정리 cause={}",
                            scoreRunId, e.getClass().getSimpleName());
                    return;
                }
                try {
                    sleeper.sleep(RECORD_RETRY.get(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.error("배치 잇기 점수 실패 기록 중단 batch_run_id={} — RUNNING 으로 남을 수 있음", scoreRunId);
                    return;
                }
            }
        }
    }

    /** 끝난 시각 — 없으면(손으로 넣은 행) 시작 시각. */
    static Instant endOf(BatchRun run) {
        return run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt();
    }
}
