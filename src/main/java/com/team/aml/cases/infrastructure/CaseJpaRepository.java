package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.AssigneeCaseCount;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.PendingApprovalRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
    @Query("""
            select new com.team.aml.cases.domain.AssigneeCaseCount(c.assigneeId, count(c))
            from InvestigationCase c
            where c.assigneeId in :assigneeIds and c.status in :statuses
            group by c.assigneeId
            """)
    List<AssigneeCaseCount> countByAssignees(@Param("assigneeIds") Collection<Long> assigneeIds,
                                             @Param("statuses") Collection<CaseStatus> statuses);

    @Override
    @Query(value = "select nextval('ops.case_no_seq')", nativeQuery = true)
    long nextCaseNoSeq();

    /**
     * 이력은 case_history_case_action_idx (case_id, action, created_at desc) 로 찾는다.
     * 승인 대기인데 SUBMIT_FOR_APPROVAL 이력이 없는 케이스는 (정상 흐름에선 없지만) 목록·건수 둘 다에서 뺀다 — 둘이 어긋나지 않게.
     */
    @Override
    @Query(value = """
            select new com.team.aml.cases.domain.PendingApprovalRow(
                       c.id, c.caseNo, c.version, c.currentDraftId, c.assigneeId, max(h.createdAt))
            from InvestigationCase c
            join CaseHistory h on h.caseId = c.id
                and h.action = com.team.aml.cases.domain.CaseHistoryAction.SUBMIT_FOR_APPROVAL
            where c.status = com.team.aml.cases.domain.CaseStatus.PENDING_APPROVAL
            group by c.id, c.caseNo, c.version, c.currentDraftId, c.assigneeId
            order by max(h.createdAt) asc, c.id asc
            """,
            countQuery = """
            select count(c) from InvestigationCase c
            where c.status = com.team.aml.cases.domain.CaseStatus.PENDING_APPROVAL
              and exists (select 1 from CaseHistory h
                          where h.caseId = c.id
                            and h.action = com.team.aml.cases.domain.CaseHistoryAction.SUBMIT_FOR_APPROVAL)
            """)
    Page<PendingApprovalRow> findPendingApproval(Pageable pageable);
}
