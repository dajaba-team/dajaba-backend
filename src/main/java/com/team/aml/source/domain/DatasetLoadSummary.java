package com.team.aml.source.domain;

/** 적재 결과. 실패 조건이 아닌 값(패턴 매칭·미연결 계좌·반올림·목록 밖 국가 은행)도 함께 담는다. */
public record DatasetLoadSummary(long fileRows, long insertedRows, PatternMatchStats patternMatch,
                                 long orphanAccounts, long amountsRounded, long unlistedCountryBanks) {
}
