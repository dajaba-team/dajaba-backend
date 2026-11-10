package com.team.aml.detection.domain;

/** 경보 거래 정렬. 같은 시각은 거래 id 로 순서를 고정한다. */
public enum AlertTransactionOrder {
    TS_ASC, TS_DESC, TRANSACTION_ID_ASC, TRANSACTION_ID_DESC
}
