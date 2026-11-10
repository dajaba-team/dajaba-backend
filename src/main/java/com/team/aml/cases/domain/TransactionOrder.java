package com.team.aml.cases.domain;

/** 근거 거래 정렬 (계약서 C3 sort=ts|transaction_id,asc|desc). 같은 시각은 거래 id 로 고정. */
public enum TransactionOrder {
    TS_ASC, TS_DESC, TRANSACTION_ID_ASC, TRANSACTION_ID_DESC
}
