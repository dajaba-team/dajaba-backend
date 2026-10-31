package com.team.aml.source.domain;

import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findById(String accountKey);
}
