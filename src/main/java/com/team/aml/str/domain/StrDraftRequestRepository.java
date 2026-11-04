package com.team.aml.str.domain;

import java.util.Optional;

/** 초안 요청 기록 저장소. 구현은 str/infrastructure (StrDraftRequestJpaRepository). */
public interface StrDraftRequestRepository {

    StrDraftRequest save(StrDraftRequest request);

    Optional<StrDraftRequest> findById(Long draftId);

    /** 케이스의 진행 중 요청은 REQUESTED 로 찾는다 (케이스마다 최대 하나, V008 partial unique). */
    Optional<StrDraftRequest> findByCaseIdAndStatus(Long caseId, StrDraftRequestStatus status);

    /** 새 draft_id. ops.str_draft_id_seq 에서 받는다 — AI 호출 전에. */
    long nextDraftId();
}
