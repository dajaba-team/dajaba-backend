package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.detection.domain.ScoreOutcome;
import com.team.aml.detection.domain.ScoreTarget;
import com.team.aml.shared.ai.AiDtos.ScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 묶음 하나의 저장 — 묶음마다 한 트랜잭션으로 커밋 (SD 명세 §6 처리 3b). AI 호출은 이 밖에서 한다. */
@Service
class ScoreChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ScoreChunkWriter.class);

    record Counts(int scored, int failed, int retryable) {}

    private final AlertScoreRepository scores;

    ScoreChunkWriter(AlertScoreRepository scores) {
        this.scores = scores;
    }

    /** 보낸 경보마다 응답 항목 하나. 응답에 없는 경보는 FAILED + 코드 없음. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Counts save(List<ScoreTarget> chunk, List<ScoreResult> results) {
        Map<String, ScoreResult> byAlertNo = new HashMap<>();
        results.forEach(r -> byAlertNo.putIfAbsent(r.alertId(), r));
        int scored = 0, failed = 0, retryable = 0;
        for (ScoreTarget t : chunk) {
            ScoreResult r = byAlertNo.get(t.payload().alertId());
            ScoreOutcome o = r == null ? ScoreOutcome.missing() : ScoreOutcome.of(r);
            if (o.scored()) {
                if (!scores.saveScore(t.alertId(), r, o.producedAt())) {
                    log.info("점수 저장 건너뜀 alert_id={} 이미 SCORED", t.alertId());
                }
                scored++;
            } else {
                scores.markFailed(t.alertId(), o.failureCode());
                failed++;
                if (o.retryable()) {
                    retryable++;
                }
            }
        }
        return new Counts(scored, failed, retryable);
    }

    /** 묶음 호출이 실패하면 그 묶음 전부 FAILED + 코드 없음 — 다시 보낼 대상 (10/29). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAllFailed(List<ScoreTarget> chunk) {
        chunk.forEach(t -> scores.markFailed(t.alertId(), null));
    }
}
