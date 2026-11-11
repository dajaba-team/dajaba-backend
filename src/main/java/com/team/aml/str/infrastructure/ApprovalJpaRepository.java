package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalDecision;
import com.team.aml.str.domain.ApprovalRepository;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * domain 의 ApprovalRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository — delete 계열이 생기지 않게 (V009 가 UPDATE·DELETE 권한 회수).
 * 정렬은 V009 인덱스 approvals_draft_created_idx(draft_id, created_at desc, id desc) 와 같은 순서.
 */
public interface ApprovalJpaRepository extends Repository<Approval, Long>, ApprovalRepository {

    Optional<Approval> findFirstByDraftIdAndDecisionOrderByCreatedAtDescIdDesc(Long draftId, ApprovalDecision decision);

    @Override
    default Optional<Approval> findLatestRejection(long draftId) {
        return findFirstByDraftIdAndDecisionOrderByCreatedAtDescIdDesc(draftId, ApprovalDecision.REJECTED);
    }
}
