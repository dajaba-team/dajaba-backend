package com.team.aml.str.infrastructure;

import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data 쪽. 도메인 StrDraftRepository 를 직접 구현하지 않는다 — 저장 뒤 current_revision_id 를 맞추는 일이 있어서
 * StrDraftRepositoryAdapter 가 이걸 감싼다.
 */
public interface StrDraftJpaRepository extends JpaRepository<StrDraft, Long> {

    Optional<StrDraft> findByCaseIdAndStatus(Long caseId, StrDraftStatus status);
}
