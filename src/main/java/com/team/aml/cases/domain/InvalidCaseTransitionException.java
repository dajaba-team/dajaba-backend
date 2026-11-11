package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;

/** 상태·반려 코드에 맞지 않는 행동. 409 INVALID_CASE_TRANSITION. */
public class InvalidCaseTransitionException extends DomainException {

    public InvalidCaseTransitionException(CaseStatus from, CaseStatus to, RejectionReasonCode rejectionCode) {
        super("허용되지 않는 케이스 상태 변경입니다: " + from + " → " + to, details(from, to, rejectionCode, null));
    }

    private InvalidCaseTransitionException(String message, Map<String, Object> details) {
        super(message, details);
    }

    /**
     * 상태가 그대로인 동작(재배정 등)이 지금 상태에서 안 될 때. "OPEN → OPEN" 같은 메시지 대신 동작 이름을 쓴다.
     * details: from_status, action (to_status 없음).
     */
    public static InvalidCaseTransitionException notAllowedIn(CaseStatus status, CaseHistoryAction action,
                                                              RejectionReasonCode rejectionCode) {
        return new InvalidCaseTransitionException(
                "지금 상태(" + status + ")에서는 할 수 없는 작업입니다: " + action,
                details(status, null, rejectionCode, action));
    }

    /**
     * 수정본 저장이 지금 케이스에서 안 될 때 — DRAFTED·보완 반려(REJECTED+NEED_REVISION)가 아니거나,
     * 요청한 초안이 케이스의 현재 초안이 아니다.
     * details: from_status, action=REVISE_DRAFT, draft_id, current_draft_id(있으면).
     */
    public static InvalidCaseTransitionException revisionNotAllowed(CaseStatus status, RejectionReasonCode rejectionCode,
                                                                    Long draftId, Long currentDraftId) {
        Map<String, Object> details = new HashMap<>(details(status, null, rejectionCode, null));
        details.put("action", "REVISE_DRAFT");
        if (draftId != null) {
            details.put("draft_id", draftId);
        }
        if (currentDraftId != null) {
            details.put("current_draft_id", currentDraftId);
        }
        boolean revisableStatus = status == CaseStatus.DRAFTED
                || (status == CaseStatus.REJECTED && rejectionCode == RejectionReasonCode.NEED_REVISION);
        String reason = revisableStatus
                ? "케이스의 현재 초안이 아닙니다: " + draftId
                : "지금 상태(" + status + (rejectionCode != null ? "+" + rejectionCode : "")
                  + ")에서는 수정본을 저장할 수 없습니다";
        return new InvalidCaseTransitionException(reason, Map.copyOf(details));
    }

    /**
     * 승인 요청·승인·반려하려는 초안이 케이스의 현재 초안이 아닐 때 (그 사이 재생성으로 초안이 바뀐 경우 등).
     * details: from_status, action, draft_id, current_draft_id(있으면), 반려 코드(있으면).
     */
    public static InvalidCaseTransitionException notCurrentDraft(CaseStatus status, RejectionReasonCode rejectionCode,
                                                                 CaseHistoryAction action, Long draftId,
                                                                 Long currentDraftId) {
        Map<String, Object> details = new HashMap<>(details(status, null, rejectionCode, action));
        details.put("draft_id", draftId);
        if (currentDraftId != null) {
            details.put("current_draft_id", currentDraftId);
        }
        return new InvalidCaseTransitionException("케이스의 현재 초안이 아닙니다: " + draftId, Map.copyOf(details));
    }

    /**
     * 메모·요약을 고칠 수 없는 상태(Case.isEditableByAssignee 가 false)에서 내용 편집을 했을 때.
     * details: from_status, action(REFRESH_SUMMARY 등 — 이력이 남지 않는 동작이라 CaseHistoryAction 이 아님), 반려 코드(있으면).
     */
    public static InvalidCaseTransitionException notEditable(CaseStatus status, RejectionReasonCode rejectionCode,
                                                             String action) {
        Map<String, Object> details = new HashMap<>(details(status, null, rejectionCode, null));
        details.put("action", action);
        return new InvalidCaseTransitionException(
                "지금 상태(" + status + (rejectionCode != null ? "+" + rejectionCode : "")
                        + ")에서는 할 수 없는 작업입니다: " + action,
                Map.copyOf(details));
    }

    // Map.copyOf 는 null 값을 못 넣는다. 값이 있는 것만 담는다(반려 코드는 REJECTED 일 때만 있음).
    private static Map<String, Object> details(CaseStatus from, CaseStatus to, RejectionReasonCode rejectionCode,
                                               CaseHistoryAction action) {
        Map<String, Object> details = new HashMap<>();
        details.put("from_status", from);
        if (action != null) {
            details.put("action", action);
        }
        if (to != null) {
            details.put("to_status", to);
        }
        if (rejectionCode != null) {
            details.put("rejection_reason_code", rejectionCode);
        }
        return Map.copyOf(details);
    }
}
