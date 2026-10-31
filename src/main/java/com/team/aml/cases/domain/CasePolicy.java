package com.team.aml.cases.domain;

import java.math.BigDecimal;

/**
 * 운영 등급 정책 (안건 2 — 등급은 cases 가 계산, AI 는 점수·추천 임계값만).
 * score ≥ mediumToHigh → HIGH, score ≥ lowToMedium → MEDIUM, 그 외 LOW (명세 §7.1).
 * 실제 경계값은 협의 전이라 기본값을 두지 않는다. 값이 없거나 잘못되면 LOW 가 아니라 POLICY_NOT_CONFIGURED.
 */
public final class CasePolicy {

    private final String policyVersion;
    private final BigDecimal lowToMedium;
    private final BigDecimal mediumToHigh;

    private CasePolicy(String policyVersion, BigDecimal lowToMedium, BigDecimal mediumToHigh) {
        this.policyVersion = policyVersion;
        this.lowToMedium = lowToMedium;
        this.mediumToHigh = mediumToHigh;
    }

    /** 0 ≤ lowToMedium < mediumToHigh ≤ 1 이어야 한다. */
    public static CasePolicy of(String policyVersion, BigDecimal lowToMedium, BigDecimal mediumToHigh) {
        if (policyVersion == null || policyVersion.isBlank() || lowToMedium == null || mediumToHigh == null) {
            throw new PolicyNotConfiguredException("정책 버전과 경계값이 모두 필요합니다");
        }
        if (lowToMedium.signum() < 0
                || lowToMedium.compareTo(mediumToHigh) >= 0
                || mediumToHigh.compareTo(BigDecimal.ONE) > 0) {
            throw new PolicyNotConfiguredException(
                    "경계값은 0 ≤ low_to_medium < medium_to_high ≤ 1 이어야 합니다: " + lowToMedium + ", " + mediumToHigh);
        }
        return new CasePolicy(policyVersion, lowToMedium, mediumToHigh);
    }

    /**
     * 점수로 등급을 계산한다. 점수는 0~1 로 확인된 모델 점수만 받는다.
     * @param recommendedThreshold AI 추천 임계값. 결과에 기록만 하고 계산에는 쓰지 않는다. null 가능
     */
    public ScoreClassification classify(BigDecimal score, BigDecimal recommendedThreshold) {
        if (score == null || score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidScoreException(score);
        }
        ScoreBand band = score.compareTo(mediumToHigh) >= 0 ? ScoreBand.HIGH
                : score.compareTo(lowToMedium) >= 0 ? ScoreBand.MEDIUM
                : ScoreBand.LOW;
        return new ScoreClassification(band, score, policyVersion, lowToMedium, mediumToHigh, recommendedThreshold);
    }

    public String getPolicyVersion() { return policyVersion; }
    public BigDecimal getLowToMedium() { return lowToMedium; }
    public BigDecimal getMediumToHigh() { return mediumToHigh; }
}
