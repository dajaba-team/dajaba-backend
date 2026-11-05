package com.team.aml.source.domain;

/** 적재 결과. 실패 조건이 아닌 보고값(반올림·목록 밖 국가 은행)도 함께 담는다. */
public record DatasetLoadSummary(long fileRows, long insertedRows, long amountsRounded, long unlistedCountryBanks) {
}
