package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;

/** 상태·반려 코드에 맞지 않는 행동. 409 INVALID_CASE_TRANSITION. */
public class InvalidCaseTransitionException extends DomainException {

    public InvalidCaseTransitionException(CaseStatus from, CaseStatus to, RejectionReasonCode rejectionCode) {
        super("허용되지 않는 케이스 상태 변경입니다: " + from + " → " + to, details(from, to, rejectionCode));
    }

    // Map.copyOf 는 null 값을 못 넣는다. 값이 있는 것만 담는다(반려 코드는 REJECTED 일 때만 있음).
    private static Map<String, Object> details(CaseStatus from, CaseStatus to, RejectionReasonCode rejectionCode) {
        Map<String, Object> details = new HashMap<>();
        details.put("from_status", from);
        if (to != null) {
            details.put("to_status", to);
        }
        if (rejectionCode != null) {
            details.put("rejection_reason_code", rejectionCode);
        }
        return Map.copyOf(details);
    }
}
