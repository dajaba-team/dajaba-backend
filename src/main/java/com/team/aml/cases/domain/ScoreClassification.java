package com.team.aml.cases.domain;

import java.math.BigDecimal;

/**
 * 등급 계산 결과와 그때 쓴 정책 스냅샷. 적용 경계와 AI 추천값을 따로 남긴다.
 * recommendedThreshold 는 기록용이며 등급 계산에 쓰지 않는다. 없으면 null.
 */
public record ScoreClassification(
        ScoreBand band,
        BigDecimal score,
        String policyVersion,
        BigDecimal lowToMedium,
        BigDecimal mediumToHigh,
        BigDecimal recommendedThreshold) {
}
