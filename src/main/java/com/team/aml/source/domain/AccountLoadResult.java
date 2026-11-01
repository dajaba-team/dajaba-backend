package com.team.aml.source.domain;

/** 파일 행 수와 실제 삽입 수. 차이는 이미 적재된 계좌·법인이다. */
public record AccountLoadResult(long fileRows, long entitiesInserted, long accountsInserted) {
}
