package com.team.aml.source.domain;

/** 적재 결과. amountsRounded는 컬럼 소수 자릿수 때문에 값이 바뀐 금액 필드 수다. 라벨 값은 담지 않는다. */
public record TransactionLoadResult(long sourceFileId, long fileRows, long insertedRows, long amountsRounded) {
}
