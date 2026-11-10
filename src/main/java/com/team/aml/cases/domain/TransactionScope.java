package com.team.aml.cases.domain;

/**
 * 거래 범위 (CASES 명세 API-08·10). EVIDENCE = 경보에 묶인 근거 거래 전체, CONTEXT = 경보 밖 참고 거래.
 * CONTEXT 는 STR 합계·근거에 넣지 않는다. 〔참고 거래를 경보에 등록하는 저장소가 아직 없다 — 지금 CONTEXT 는 늘 빈 목록〕
 */
public enum TransactionScope {
    EVIDENCE, CONTEXT
}
