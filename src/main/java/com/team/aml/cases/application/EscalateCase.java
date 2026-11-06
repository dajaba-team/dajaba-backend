package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에스컬레이션 REVIEWING → ESCALATED (CASES 명세 API-07, 계약서 A4). 담당 조사역만, 사유 필수.
 * AI 를 부르지 않는다 — 초안 생성은 화면이 따로 요청한다(str).
 * 검사 순서: 케이스 404 → version 409 → 담당자 403 NOT_ASSIGNEE → 상태 409 INVALID_CASE_TRANSITION → 사유(COMMENT_REQUIRED).
 */
@Service
public class EscalateCase {

    private final CaseChanges changes;
    private final CurrentUser currentUser;

    public EscalateCase(CaseChanges changes, CurrentUser currentUser) {
        this.changes = changes;
        this.currentUser = currentUser;
    }

    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, String comment) {
        long actorId = currentUser.require().userId();
        Case c = changes.load(caseNo, expectedVersion);
        changes.requireAssignee(c, actorId);
        c.escalate(actorId, comment);
        return changes.save(c);
    }
}
