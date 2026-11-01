package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.Account;
import com.team.aml.source.domain.AccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepository extends JpaRepository<Account, String>, AccountRepository {
}
