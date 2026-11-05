package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * domain 의 CaseRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository 를 상속해 delete 계열 메서드가 생기지 않게 한다.
 */
public interface CaseJpaRepository extends Repository<Case, Long>, CaseRepository, CaseVersionBump {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvestigationCase c where c.id = :id")
    Optional<Case> findForUpdate(@Param("id") Long id);
}
