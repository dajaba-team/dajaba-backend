package com.team.aml.str.api;

import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.str.application.ListPendingApprovals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 승인함 한 줄 (계약서 P1 items, 11/4 채영):
 * {draft_no, case_no, assignee{login_id, display_name}, submitted_at, verification{verification_id, revision_no, status, passed},
 *  last_rejection{reason_code, comment, decided_at} 또는 null, case_version}
 * + was_assignee · available_actions (자기 승인 막기 표시 — 계약서 5.5에 추가 필요, PR 본문 "리뷰어에게").
 * 본문은 없다. 시각은 UTC Z 초 단위(계약서 2.4) — cases 가 DB 값 그대로 주므로 여기서 자른다.
 */
public record PendingApprovalResponse(String draftNo, String caseNo, AssigneeResponse assignee, Instant submittedAt,
                                      VerificationResponse verification, RejectionResponse lastRejection,
                                      long caseVersion, boolean wasAssignee, List<String> availableActions) {

    public record AssigneeResponse(String loginId, String displayName) {}

    public record VerificationResponse(String verificationId, int revisionNo, String status, boolean passed) {}

    public record RejectionResponse(String reasonCode, String comment, Instant decidedAt) {}

    static PendingApprovalResponse from(ListPendingApprovals.Item item) {
        AssigneeResponse assignee = item.assignee() == null ? null
                : new AssigneeResponse(item.assignee().loginId(), item.assignee().displayName());
        VerificationResponse verification = item.verification() == null ? null
                : new VerificationResponse(item.verification().verificationId(), item.verification().revisionNo(),
                item.verification().status(), item.verification().passed());
        RejectionResponse rejection = item.lastRejection() == null ? null
                : new RejectionResponse(item.lastRejection().reasonCode(), item.lastRejection().comment(),
                seconds(item.lastRejection().decidedAt()));
        return new PendingApprovalResponse(DisplayNumber.draftNo(item.draftId()), item.caseNo(), assignee,
                seconds(item.submittedAt()), verification, rejection, item.caseVersion(),
                item.wasAssignee(), item.availableActions());
    }

    private static Instant seconds(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.SECONDS);
    }
}
