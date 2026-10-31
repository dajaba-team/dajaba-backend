package com.team.aml.source.domain;

/** 패턴 행과 거래 행의 매칭 결과. 기록용이며 적재 실패 조건이 아니다 (SD 명세 §3). */
public record PatternMatchStats(long matched, long unmatched, long multiMatched) {
}
