package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.CustomerAttributes;
import com.team.aml.source.domain.CustomerAttributesRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAttributesJpaRepository extends JpaRepository<CustomerAttributes, String>, CustomerAttributesRepository {
}
