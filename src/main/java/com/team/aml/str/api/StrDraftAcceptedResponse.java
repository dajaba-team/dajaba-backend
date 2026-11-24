package com.team.aml.str.api;

import com.team.aml.str.application.RequestStrDraft;

/**
 * 202 응답 (S1 비동기, aml.str.draft.async=true). 초안은 아직 없다 — 끝났는지는 요청 기록(str_draft_requests)이 원본이고,
 * 화면 상태 조회 API 는 후속 PR. 이름은 전역 SNAKE_CASE 설정이 바꾼다.
 */
public record StrDraftAcceptedResponse(String caseNo, String draftNo, String requestStatus) {

    static StrDraftAcceptedResponse from(RequestStrDraft.Accepted a) {
        return new StrDraftAcceptedResponse(a.caseNo(), a.draftNo(), a.requestStatus());
    }
}
