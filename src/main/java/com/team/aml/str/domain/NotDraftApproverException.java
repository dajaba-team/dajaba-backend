package com.team.aml.str.domain;

import com.team.aml.shared.domain.ForbiddenException;

/**
 * 이 초안을 승인한 사람이 아닌 승인자가 모의 제출(P4)하려 할 때. 403 NOT_DRAFT_APPROVER (11/17 결정 — 제출자 = 승인한 사람만).
 * 역할 문제(NOT_APPROVER)·담당 문제(NOT_ASSIGNEE)처럼 "이 사람이 할 수 있나"라서 409 가 아니라 403.
 * 누가 승인했는지(사람 id·이름)는 details 에 넣지 않는다 — 화면은 버튼을 숨기는 게 먼저(S2 available_actions REPORT).
 */
public class NotDraftApproverException extends ForbiddenException {
    public NotDraftApproverException() {
        super("이 보고서를 승인한 사람만 모의 제출할 수 있습니다");
    }
}
