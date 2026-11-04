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
