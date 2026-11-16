package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 경보 점검 한 줄 (R1). 계좌는 내부 키 그대로 — 가리는 건 api 응답 몫. 정답 칸(is_laundering·pattern_*)은 읽지 않는다.
 * 점수 칸(score·threshold·modelVersion·dataQualityStatus)은 SCORED 일 때만 있고 PENDING·FAILED 면 null.
 *
 * @param createdAt 경보 생성 시각(시스템 시각, 초까지)
 */
public record AlertReviewItem(long alertId, String alertNo, LocalDate batchDate, String ruleId, int ruleVersion,
                              String typologyHint, String accountKey, LocalDateTime windowStart,
                              LocalDateTime windowEnd, LocalDateTime referenceTs, int transactionCount,
                              String scoreStatus, String scoreFailureCode, BigDecimal score, BigDecimal threshold,
                              String modelVersion, String dataQualityStatus, boolean caseOpened, Instant createdAt) {

    /** 점수 배치를 다시 돌리면 다시 보내는 실패인가 — FAILED 이고 코드 NULL·COMPUTE_ERROR (점수 배치 대상 SQL과 같은 규칙). */
    public boolean retryable() {
        return "FAILED".equals(scoreStatus) && ScoreOutcome.retryable(scoreFailureCode);
    }
}
