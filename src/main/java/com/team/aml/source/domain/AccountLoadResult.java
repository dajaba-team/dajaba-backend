package com.team.aml.source.domain;

/**
 * 파일 행 수와 실제 삽입 수. 차이는 값이 같아 건너뛴 기존 계좌·법인이다.
 * unlistedCountryBanks는 국가 목록에 없어 bank_country를 비워 둔 "{X} Bank #n" 은행 수다(보고만).
 */
public record AccountLoadResult(long fileRows, long entitiesInserted, long accountsInserted, long unlistedCountryBanks) {
}
