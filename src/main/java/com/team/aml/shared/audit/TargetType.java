package com.team.aml.shared.audit;

/** access_logs.target_type — 무엇을 봤나 (레퍼런스 §3.4). V004_3 CHECK 와 같은 목록. 로그인·로그아웃은 대상 없음(null). */
public enum TargetType {
    CASE,
    STR_DRAFT,
    ALERT,
    USER
}