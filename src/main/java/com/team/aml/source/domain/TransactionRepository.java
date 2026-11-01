package com.team.aml.source.domain;

import java.util.Optional;

public interface TransactionRepository {
    Optional<Transaction> findById(Long transactionId);
    boolean existsBy();   // 적재 여부 확인용
}
