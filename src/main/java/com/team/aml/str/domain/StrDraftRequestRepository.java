package com.team.aml.str.domain;

import java.util.Optional;

/** 초안 요청 기록 저장소. 구현은 str/infrastructure (StrDraftRequestJpaRepository). 지우지 않는다 — delete 없음(V008 revoke delete). */
public interface StrDraftRequestRepository {

    StrDraftRequest save(StrDraftRequest request);

    Optional<StrDraftRequest> findById(Long draftId);

    /**
     * 케이스의 진행 중(REQUESTED) 요청. 케이스마다 최대 하나라(V008 partial unique) Optional 이다.
     * 다른 상태로 찾는 메서드는 일부러 두지 않는다 — SUCCEEDED·FAILED 는 케이스마다 여러 행일 수 있다 (#52 리뷰).
     */
    Optional<StrDraftRequest> findRequestedByCaseId(Long caseId);

    /** 새 draft_id. ops.str_draft_id_seq 에서 받는다 — AI 호출 전에. */
    long nextDraftId();
}
