package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담당자 변경 (CASES 명세 API-04, 계약서 A2). ADMIN 만 — 역할은 SecurityConfig 가 URL 로 막는다(403 FORBIDDEN, 권한 표 7).
 * 상태·반려 코드는 그대로. 이력 REASSIGN 에 넘긴 사람(from_assignee_id)·받은 사람(to_assignee_id)·사유(comment)를 남긴다 (V010).
 * 검사 순서: 케이스 404 → version 409 → 대상(없는 login_id 404 USER_NOT_FOUND, 비활성·조사역 아님 409 INVALID_ASSIGNEE)
 * → 상태 409 INVALID_CASE_TRANSITION(REVIEWING·ESCALATED·DRAFTED·REJECTED 만). 같은 담당자면 no-op(version 그대로).
 */
@Service
public class ReassignCase {

    private final CaseChanges changes;
    private final ActiveInvestigators investigators;
    private final CurrentUser currentUser;

    public ReassignCase(CaseChanges changes, ActiveInvestigators investigators, CurrentUser currentUser) {
        this.changes = changes;
        this.investigators = investigators;
        this.currentUser = currentUser;
    }

    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, String assigneeLoginId, String comment) {
        long actorId = currentUser.require().userId();
        Case c = changes.load(caseNo, expectedVersion);
        long assigneeId = investigators.require(caseNo, assigneeLoginId);
        String note = comment == null || comment.isBlank() ? null : comment;
        return c.reassign(assigneeId, actorId, note) ? changes.save(c) : changes.result(c);
    }
}
