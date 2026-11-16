package com.team.aml.detection.api;

import com.team.aml.detection.domain.AlertReviewItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 경보 목록 한 줄 (R1). 계좌는 가린 값과 account_ref 만 — account_key·entity_id·정답 칸 없음(계약서 2.4).
 * latest_score 는 점수가 있을 때만(SCORED), PENDING·FAILED 면 null — 0점으로 채우지 않는다.
 */
public record AdminAlertListItemResponse(String alertNo, LocalDate batchDate, String ruleId, int ruleVersion,
                                         String typologyHint, String accountNo, String accountRef,
                                         LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime referenceTs,
                                         int transactionCount, String scoreStatus, String scoreFailureCode,
                                         boolean retryable, LatestScore latestScore, boolean caseOpened,
                                         Instant createdAt) {

    public record LatestScore(BigDecimal score, BigDecimal threshold, String dataQualityStatus, String modelVersion) {
    }

    static AdminAlertListItemResponse from(AlertReviewItem a) {
        return new AdminAlertListItemResponse(a.alertNo(), a.batchDate(), a.ruleId(), a.ruleVersion(),
                a.typologyHint(), DetectionAccountDisplay.maskedNo(a.accountKey()),
                DetectionAccountDisplay.ref(a.accountKey()), a.windowStart(), a.windowEnd(), a.referenceTs(),
                a.transactionCount(), a.scoreStatus(), a.scoreFailureCode(), a.retryable(),
                a.score() == null ? null
                        : new LatestScore(a.score(), a.threshold(), a.dataQualityStatus(), a.modelVersion()),
                a.caseOpened(), a.createdAt());
    }
}
