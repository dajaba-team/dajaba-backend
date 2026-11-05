package com.team.aml.source.domain;

import java.time.LocalDateTime;

/** 적재 직후 원천 테이블 집계. 건수와 시각 범위만 담는다. transactionAccounts는 거래에 나오는 서로 다른 계좌 수다. */
public record DatasetStats(long transactions, long launderingTransactions, long selfTransfers,
                           long patternTransactions, long patternEvents,
                           LocalDateTime firstTs, LocalDateTime lastTs,
                           long accounts, long entities, long transactionAccounts, long orphanAccounts) {
}
