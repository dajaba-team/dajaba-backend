package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.LegalEntity;
import com.team.aml.source.domain.LegalEntityRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalEntityJpaRepository extends JpaRepository<LegalEntity, String>, LegalEntityRepository {
}
