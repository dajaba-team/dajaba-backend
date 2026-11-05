package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftStatus;
import org.springframework.data.repository.Repository;
import java.util.Optional;

/**
 * Spring Data 쪽. 도메인 StrDraftRepository 를 직접 구현하지 않는다 — 저장 뒤 current_revision_id 를 맞추는 일이 있어서
 * StrDraftRepositoryAdapter 가 이걸 감싼다. JpaRepository 가 아니라 Repository 를 상속해 delete 계열이 생기지 않게 한다
* — V008 이 DELETE 권한을 회수함 (StrDraftRequestJpaRepository·CaseJpaRepository 와 같음, #52 리뷰).
 */
public interface StrDraftJpaRepository extends Repository<StrDraft, Long> {

    Optional<StrDraft> findById(Long id);
    Optional<StrDraft> findByCaseIdAndStatus(Long caseId, StrDraftStatus status);
}
