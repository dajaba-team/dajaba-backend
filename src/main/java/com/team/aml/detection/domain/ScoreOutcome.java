package com.team.aml.detection.domain;

import com.team.aml.shared.ai.AiDtos.ScoreResult;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 점수 응답 항목 하나를 어떻게 저장할지 (SD 명세 §6 저장 규칙, 10/29 SCRUM-41).
 * <ul>
 *   <li>OK·SHORT_HISTORY·MISSING_INPUT 이고 저장 칸이 다 있으면 SCORED — alert_scores 한 행.</li>
 *   <li>FAILED 면 FAILED + failure_code. 4값이 아니면 null(다시 보낼 대상).</li>
 *   <li>그 밖(모르는 상태, prediction_id·버전 없음·64자 넘음, produced_at 을 못 읽음)은 FAILED + null.</li>
 * </ul>
 * 0점·LOW 로 바꾸지 않는다(안건 4). 형식 검사(점수 0~1, 묶음 alert_id 대조)는 AiClient 가 먼저 한다(SCRUM-128·204).
 */
public record ScoreOutcome(boolean scored, String failureCode, OffsetDateTime producedAt) {

    private static final Set<String> SCORED_STATUSES = Set.of("OK", "SHORT_HISTORY", "MISSING_INPUT");
    private static final Set<String> FAILURE_CODES = Set.of(
            "TRANSACTION_NOT_FOUND", "HISTORY_NOT_SUPPORTED", "REQUIRED_INPUT_MISSING", "COMPUTE_ERROR");
    /** alert_scores 의 prediction_id·model_version·feature_version 길이 (V005_1 varchar(64)). */
    private static final int MAX_TEXT = 64;

    public static ScoreOutcome of(ScoreResult r) {
        String status = r.dataQualityStatus();
        if ("FAILED".equals(status)) {
            String code = r.failureCode();
            return failed(code != null && FAILURE_CODES.contains(code) ? code : null);
        }
        if (status == null || !SCORED_STATUSES.contains(status) || r.score() == null || r.meta() == null
                || !fits(r.predictionId(), false)
                || !fits(r.meta().modelVersion(), true) || !fits(r.meta().featureVersion(), true)) {
            return failed(null);
        }
        OffsetDateTime producedAt = parse(r.producedAt());
        return producedAt == null ? failed(null) : new ScoreOutcome(true, null, producedAt);
    }

    /** 응답에 그 경보가 없을 때 — FAILED + null (명세 §6 저장 규칙). */
    public static ScoreOutcome missing() {
        return failed(null);
    }

    /** 다시 보내면 결과가 달라질 수 있는 실패 — 코드 없음(묶음 실패·응답 이상)·COMPUTE_ERROR (10/29). */
    public static boolean retryable(String failureCode) {
        return failureCode == null || "COMPUTE_ERROR".equals(failureCode);
    }

    public boolean retryable() {
        return !scored && retryable(failureCode);
    }

    private static ScoreOutcome failed(String code) {
        return new ScoreOutcome(false, code, null);
    }

    /** 버전은 빈 문자열 허용(SCRUM-204 계약 기준), prediction_id 는 빈 값 불가. 둘 다 64자까지. */
    private static boolean fits(String v, boolean blankOk) {
        return v != null && v.length() <= MAX_TEXT && (blankOk || !v.isBlank());
    }

    /** 시스템 시각이라 오프셋이 있어야 한다(Z 또는 +00:00). 없으면 timestamptz 로 못 넣는다. */
    private static OffsetDateTime parse(String v) {
        try {
            return v == null ? null : OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
