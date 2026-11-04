package com.team.aml.str.domain;

import java.util.Optional;

/** STR 초안 저장소. 구현은 str/infrastructure (StrDraftRepositoryAdapter). */
public interface StrDraftRepository {

    /**
     * 새 초안이면 INSERT(초안 → 수정본 1번), 이미 읽어 온 초안이면 바뀐 것만 반영(새 수정본 INSERT).
     * 저장 뒤 current_revision_id 를 마지막 수정본으로 맞춘다.
     */
    StrDraft save(StrDraft draft);

    Optional<StrDraft> findById(Long draftId);

    /** 케이스의 사용 중(ACTIVE) 초안. 케이스마다 최대 하나(V008 partial unique). */
    Optional<StrDraft> findActiveByCaseId(Long caseId);

}
