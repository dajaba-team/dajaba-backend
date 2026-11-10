package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.InvalidAssigneeException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 배정 OPEN → REVIEWING (CASES 명세 API-03, 계약서 A1). 조사역은 본인에게, ADMIN 은 지정한 조사역에게.
 * assignee 는 작업자가 아니라 업무 입력이라 body 로 받는다(03_shared §4) — 작업자는 CurrentUser.
 *
 * <p>검사 순서: 케이스 404 → version 409 → 배정 대상(조사역이 남을 지정 409 INVALID_ASSIGNEE·SELF_ONLY,
 * 없는 사용자 404 USER_NOT_FOUND, 비활성·조사역 아님 409 INVALID_ASSIGNEE) → 상태 409 INVALID_CASE_TRANSITION.
 * 동시에 두 사람이 맡으면 뒤 요청은 커밋 때 409 CONCURRENT_UPDATE.
 */
@Service
public class AssignCase {

    private final CaseChanges changes;
    private final ActiveInvestigators investigators;
    private final CurrentUser currentUser;

    public AssignCase(CaseChanges changes, ActiveInvestigators investigators, CurrentUser currentUser) {
        this.changes = changes;
        this.investigators = investigators;
        this.currentUser = currentUser;
    }

    /**
     * @param assigneeLoginId 배정할 조사역 login_id. 조사역 본인 배정이면 null(또는 본인 login_id). ADMIN 은 필수.
     */
    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, String assigneeLoginId) {
        AppUserDetails me = currentUser.require();
        Case c = changes.load(caseNo, expectedVersion);
        c.assign(resolveAssignee(me, caseNo, assigneeLoginId), me.userId());
        return changes.save(c);
    }

    private long resolveAssignee(AppUserDetails me, String caseNo, String assigneeLoginId) {
        boolean admin = Role.ADMIN.name().equals(me.role());
        if (!admin && assigneeLoginId != null && !assigneeLoginId.equals(me.loginId())) {
            throw new InvalidAssigneeException(caseNo, assigneeLoginId, InvalidAssigneeException.Reason.SELF_ONLY);
        }
        // ADMIN 이 대상을 안 주면 본인 — ADMIN 은 조사역이 아니라 INVALID_ASSIGNEE 가 된다
        String target = assigneeLoginId != null ? assigneeLoginId : me.loginId();
        return investigators.require(caseNo, target);
    }
}
