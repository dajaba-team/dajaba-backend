package com.team.aml.str.api;

import com.team.aml.str.application.StrCaseChange;

import java.time.Instant;

/**
 * 승인 요청(S6)·승인(P2)·반려(P3) 응답 — 케이스 공통 변경 응답과 같은 모양 (계약서 5.3).
 * {case_no, status, assignee{login_id, display_name}, rejection_reason_code, current_draft_no, version, updated_at}.
 * 이름은 전역 SNAKE_CASE 설정이 바꾼다(@JsonProperty 를 붙이지 않는다).
 */
public record StrCaseChangeResponse(String caseNo, String status, AssigneeResponse assignee,
                                    String rejectionReasonCode, String currentDraftNo,
                                    long version, Instant updatedAt) {

    public record AssigneeResponse(String loginId, String displayName) {}

    static StrCaseChangeResponse from(StrCaseChange c) {
        AssigneeResponse assignee = c.assignee() == null ? null
                : new AssigneeResponse(c.assignee().loginId(), c.assignee().displayName());
        return new StrCaseChangeResponse(c.caseNo(), c.status(), assignee, c.rejectionReasonCode(),
                c.currentDraftNo(), c.version(), c.updatedAt());
    }
}
