package com.team.aml.detection.domain;

/** 경보 점검 목록 정렬 (SD 명세 §7). 점수 없는 경보는 오름·내림 모두 맨 뒤(C1과 같은 규칙). SQL 은 이 enum 에서만 고른다. */
public enum AlertReviewOrder {
    SCORE_DESC,
    SCORE_ASC,
    ALERT_NO_ASC,
    ALERT_NO_DESC,
    CREATED_AT_ASC,
    CREATED_AT_DESC
}
