package com.team.aml.detection.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.detection.domain.AlertReviewDetail;
import com.team.aml.detection.domain.AlertReviewItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 경보 상세 (R2) = 목록 한 줄 + rule_facts + 점수 세부. rule_facts 는 저장값 그대로(화면이 풀어 씀, 계약서 5.7).
 * reasons 는 관측값·기준값·기여값 구조 그대로 — 기여값을 관측값처럼 바꾸지 않는다(SD §7).
 * reasons 도 저장값 그대로다. AI 규약(scores.py StrictModel)에 계좌 칸이 없어서 가리지 않는다 — 칸이 바뀌면 가리기를 더한다.
 * context_transaction_ids 는 번호만 준다. 경보 밖 참고 거래라 /{alert_no}/transactions 로는 열리지 않는다.
 */
public record AdminAlertDetailResponse(String alertNo, LocalDate batchDate, String ruleId, int ruleVersion,
                                       String typologyHint, String accountNo, String accountRef,
                                       LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime referenceTs,
                                       int transactionCount, String scoreStatus, String scoreFailureCode,
                                       boolean retryable, boolean caseOpened, Instant createdAt, JsonNode ruleFacts,
                                       LatestScore latestScore) {

    public record LatestScore(BigDecimal score, BigDecimal threshold, String dataQualityStatus, String modelVersion,
                              String featureVersion, String predictionId, Instant producedAt, JsonNode reasons,
                              JsonNode evidenceTransactionIds, JsonNode contextTransactionIds) {
    }

    static AdminAlertDetailResponse from(AlertReviewDetail d) {
        AlertReviewItem a = d.item();
        return new AdminAlertDetailResponse(a.alertNo(), a.batchDate(), a.ruleId(), a.ruleVersion(),
                a.typologyHint(), DetectionAccountDisplay.maskedNo(a.accountKey()),
                DetectionAccountDisplay.ref(a.accountKey()), a.windowStart(), a.windowEnd(), a.referenceTs(),
                a.transactionCount(), a.scoreStatus(), a.scoreFailureCode(), a.retryable(), a.caseOpened(),
                a.createdAt(), d.ruleFacts(),
                a.score() == null ? null
                        : new LatestScore(a.score(), a.threshold(), a.dataQualityStatus(), a.modelVersion(),
                                d.featureVersion(), d.predictionId(), d.producedAt(), d.reasons(),
                                d.evidenceTransactionIds(), d.contextTransactionIds()));
    }
}
