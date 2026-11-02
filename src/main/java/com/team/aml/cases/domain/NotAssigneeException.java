package com.team.aml.cases.domain;

import com.team.aml.shared.domain.ForbiddenException;

import java.util.HashMap;
import java.util.Map;

/** 403 NOT_ASSIGNEE — 담당 조사역만 할 수 있는 일을 다른 사람이 요청했다 (SCRUM-67: ForbiddenException 상속). */
public class NotAssigneeException extends ForbiddenException {

    public NotAssigneeException(String caseNo) {
        super("담당 조사역만 할 수 있습니다: " + caseNo, details(caseNo));
    }

    private static Map<String, Object> details(String caseNo) {
        Map<String, Object> d = new HashMap<>();
        d.put("case_no", caseNo);
        return d;
    }
}
