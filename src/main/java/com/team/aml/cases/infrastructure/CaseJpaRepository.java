package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    @Override
    @Query("""
            select c from InvestigationCase c
            where c.status in :statuses
              and (:assigneeId is null or c.assigneeId = :assigneeId)
              and c.createdAt >= :createdFrom and c.createdAt < :createdTo
            """)
    Page<Case> search(@Param("statuses") Collection<CaseStatus> statuses, @Param("assigneeId") Long assigneeId,
                      @Param("createdFrom") Instant createdFrom, @Param("createdTo") Instant createdTo,
                      Pageable pageable);

    @Override
    @Query(value = "select nextval('ops.case_no_seq')", nativeQuery = true)
    long nextCaseNoSeq();
}
