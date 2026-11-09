package com.team.aml.str.api;

import com.team.aml.str.application.RequestStrDraft;

/** 201 응답 (계약서 S1). 초안 본문은 S2 GET /str-drafts/{draft_no} 로 읽는다. */
public record StrDraftCreatedResponse(String caseNo, String caseStatus, long caseVersion, String draftNo) {

    static StrDraftCreatedResponse from(RequestStrDraft.Created c) {
        return new StrDraftCreatedResponse(c.caseNo(), c.caseStatus(), c.caseVersion(), c.draftNo());
    }
}
