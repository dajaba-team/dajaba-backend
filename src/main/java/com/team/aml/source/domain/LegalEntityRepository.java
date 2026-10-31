package com.team.aml.source.domain;

import java.util.Optional;

public interface LegalEntityRepository {
    Optional<LegalEntity> findById(String entityId);
}
