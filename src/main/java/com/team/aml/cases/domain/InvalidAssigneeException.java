package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;

/**
 * 409 INVALID_ASSIGNEE — 배정 대상이 활성 조사역이 아니다 (CASES 명세 API-03·04, 03_shared §2 〔초안〕).
 * 조사역이 본인 말고 다른 사람을 지정한 경우도 여기로 온다(details.reason = SELF_ONLY).
 * 명세는 그 경우를 400 으로 적었지만 지금 shared 에 400 업무 예외가 없어 409 로 둔다 — PR 에 질문으로 남김.
 */
public class InvalidAssigneeException extends DomainException {

    public enum Reason { NOT_ACTIVE_INVESTIGATOR, SELF_ONLY }

    public InvalidAssigneeException(String caseNo, String assigneeLoginId, Reason reason) {
        super(message(reason), details(caseNo, assigneeLoginId, reason));
    }

    private static String message(Reason reason) {
        return reason == Reason.SELF_ONLY
                ? "조사역은 본인에게만 배정할 수 있습니다"
                : "활성 조사역에게만 배정할 수 있습니다";
    }

    private static Map<String, Object> details(String caseNo, String assigneeLoginId, Reason reason) {
        Map<String, Object> d = new HashMap<>();
        d.put("case_no", caseNo);
        d.put("assignee", assigneeLoginId);
        d.put("reason", reason.name());
        return d;
    }
}
