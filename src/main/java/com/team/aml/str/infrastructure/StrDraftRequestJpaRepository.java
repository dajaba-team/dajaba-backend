package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrDraftRequestStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * domain 의 StrDraftRequestRepository 를 Spring Data 가 구현하게 한다.
 * JpaRepository 가 아니라 Repository 를 상속해 delete 계열이 생기지 않게 한다 — V008 이 DELETE 권한을 회수함 (CaseJpaRepository 와 같음, #52 리뷰).
 */
public interface StrDraftRequestJpaRepository extends Repository<StrDraftRequest, Long>, StrDraftRequestRepository {

    @Override
    default Optional<StrDraftRequest> findRequestedByCaseId(Long caseId) {
        return findByCaseIdAndStatus(caseId, StrDraftRequestStatus.REQUESTED);
    }

    /** findRequestedByCaseId 전용. domain 에는 열지 않는다(REQUESTED 가 아니면 여러 행일 수 있다). */
    Optional<StrDraftRequest> findByCaseIdAndStatus(Long caseId, StrDraftRequestStatus status);

    @Override
    @Query(value = "select nextval('ops.str_draft_id_seq')", nativeQuery = true)
    long nextDraftId();
}
