package com.team.aml.cases.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 케이스를 만들 때의 점수·모델·정책 스냅샷 (V013, SCRUM-236·237, CASES 명세 §7.1). 만든 뒤에는 바뀌지 않는다.
 * 목록 점수 정렬(API-01)도 이 점수로 한다 — 경보 점수는 경보당 1행이고 바뀌지 않아서(V005_1) 지금 점수와 같다.
 *
 * <p>V013 이전에 만든 케이스는 점수만 있고 정책 세 칸은 null 이다(그때 기록이 없음). 점수까지 없으면 이 값 자체가 null.
 *
 * @param scoreId        ops.alert_scores.id
 * @param policyVersion  그때 쓴 CasePolicy 버전 — 정책 세 칸은 함께 있거나 함께 없다
 */
@Embeddable
public record OpenedScore(
        @Column(name = "opened_score_id", updatable = false) Long scoreId,
        @Column(name = "opened_score", updatable = false) BigDecimal score,
        @Column(name = "opened_model_version", updatable = false, length = 64) String modelVersion,
        @Column(name = "opened_feature_version", updatable = false, length = 64) String featureVersion,
        @Column(name = "opened_policy_version", updatable = false, length = 64) String policyVersion,
        @Column(name = "opened_low_to_medium", updatable = false) BigDecimal lowToMedium,
        @Column(name = "opened_medium_to_high", updatable = false) BigDecimal mediumToHigh) {

    /** 생성 배치용 — 점수와 그때 등급을 정한 정책 결과(ScoreClassification)를 함께 남긴다. */
    public static OpenedScore of(long scoreId, String modelVersion, String featureVersion, ScoreClassification grade) {
        Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(grade.score(), "score");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(featureVersion, "featureVersion");
        return new OpenedScore(scoreId, grade.score(), modelVersion, featureVersion,
                grade.policyVersion(), grade.lowToMedium(), grade.mediumToHigh());
    }
}
