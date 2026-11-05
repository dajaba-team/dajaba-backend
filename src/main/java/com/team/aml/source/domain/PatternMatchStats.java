package com.team.aml.source.domain;

/** 패턴 행과 거래 행의 매칭 결과. 미매칭·다중매칭이 하나라도 있으면 적재 실패다 (DatasetExpectation). */
public record PatternMatchStats(long matched, long unmatched, long multiMatched) {
}
