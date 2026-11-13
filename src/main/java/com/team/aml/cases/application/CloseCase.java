package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초안 없는 종결 → CLOSED (CASES 명세 API-06, 계약서 A5 POST /cases/{case_no}/close). 담당 조사역만, 사유·설명 필수.
 * 초안이 있으면 409 CLOSE_REQUIRES_STR — 그 종결은 str 의 close-with-draft(A6)가 초안 SUPERSEDED 와 같은 트랜잭션에서
 * cases 공개 기능 {@link CloseCaseWithDraft} 를 불러서 한다(명세 §6.2, SCRUM-235).
 * 검사 순서: 케이스 404 → version 409 → 담당자 403 → 상태 409 INVALID_CASE_TRANSITION → 초안 409 CLOSE_REQUIRES_STR → 사유.
 */
@Service
public class CloseCase {

    private final CaseChanges changes;
    private final CurrentUser currentUser;

    public CloseCase(CaseChanges changes, CurrentUser currentUser) {
        this.changes = changes;
        this.currentUser = currentUser;
    }

    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, CloseReason reason, String comment) {
        long actorId = currentUser.require().userId();
        Case c = changes.load(caseNo, expectedVersion);
        changes.requireAssignee(c, actorId);
        c.closeWithoutDraft(actorId, reason, comment);
        return changes.save(c);
    }
}
