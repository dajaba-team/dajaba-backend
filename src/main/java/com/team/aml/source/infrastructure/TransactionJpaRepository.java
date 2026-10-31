package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.Transaction;
import com.team.aml.source.domain.TransactionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionJpaRepository extends JpaRepository<Transaction, Long>, TransactionRepository {
}
