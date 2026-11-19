package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.user.domain.Role;
import org.springframework.security.access.AccessDeniedException;

/**
 * 질문 답변(A8·C9) 권한 — 버튼(ASK_QUESTION)과 같은 규칙 {@link CaseAction#canAsk} (설계 문서 7.2, SCRUM-302).
 * 담당 아닌 조사역 403 NOT_ASSIGNEE. 관리자는 SecurityConfig URL 규칙이 먼저 403 FORBIDDEN 으로 막고, 여기는 그 뒤 방어선.
 */
final class CaseQuestionAccess {

    private CaseQuestionAccess() {}

    static void require(Case c, AppUserDetails me) {
        Role role = Role.valueOf(me.role());
        if (CaseAction.canAsk(c, me.userId(), role)) {
            return;
        }
        if (role == Role.INVESTIGATOR) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        throw new AccessDeniedException("질문 답변은 담당 조사역·승인자만 할 수 있습니다");   // → 403 FORBIDDEN
    }
}
