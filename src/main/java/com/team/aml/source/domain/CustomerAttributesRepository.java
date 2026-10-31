package com.team.aml.source.domain;

import java.util.Optional;

public interface CustomerAttributesRepository {
    Optional<CustomerAttributes> findById(String entityId);
}
