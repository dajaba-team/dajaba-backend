package com.team.aml.cases.domain;

/** 종결 사유. CLOSED 는 범죄가 없다는 확정이나 학습 정답이 아니다 (명세 API-06). */
public enum CloseReason {
    FALSE_POSITIVE,  // 오탐
    DUPLICATE,       // 중복 경보
    NO_ACTION        // 확인한 근거 범위에서 추가 조치 불필요
}
