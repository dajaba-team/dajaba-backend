package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;

/**
 * 409 INVALID_ASSIGNEE — 배정 대상이 활성 조사역이 아니다 (CASES 명세 API-03·04, 03_shared §2).
 * details.reason 은 NOT_ACTIVE_INVESTIGATOR 하나 — 조사역이 남을 지정하던 SELF_ONLY 는 배정이 ADMIN 전용이 되며
 * 닿을 길이 없어 지웠다(SCRUM-339). reason 칸은 프론트 문구 분기용으로 그대로 둔다.
 */
public class InvalidAssigneeException extends DomainException {

    public enum Reason { NOT_ACTIVE_INVESTIGATOR }

    public InvalidAssigneeException(String caseNo, String assigneeLoginId, Reason reason) {
        super("활성 조사역에게만 배정할 수 있습니다", details(caseNo, assigneeLoginId, reason));
    }

    private static Map<String, Object> details(String caseNo, String assigneeLoginId, Reason reason) {
        Map<String, Object> d = new HashMap<>();
        d.put("case_no", caseNo);
        d.put("assignee", assigneeLoginId);
        d.put("reason", reason.name());
        return d;
    }
}
