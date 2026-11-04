package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 케이스 생성 대상 경보와 그 점수 (CASES 명세 §6.1). 등급은 담지 않는다 — cases 의 CasePolicy 가 계산한다 (안건 2).
 * entityId 는 법인 미확인이면 null. dataQualityStatus 는 AI 응답 그대로(OK·SHORT_HISTORY·MISSING_INPUT).
 */
public record AlertForCaseOpening(long alertId, String alertNo, String accountKey, String entityId,
                                  LocalDateTime referenceTs, long scoreId, BigDecimal score,
                                  BigDecimal recommendedThreshold, String modelVersion, String featureVersion,
                                  String dataQualityStatus, String predictionId) {
}
