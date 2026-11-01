package com.team.aml.cases.domain;

/** 운영 등급. CasePolicy 가 계산한다 (안건 2). 높은 등급은 범죄 확정이 아니다. */
public enum ScoreBand {
    HIGH,    // 케이스 생성 대상
    MEDIUM,
    LOW
}
