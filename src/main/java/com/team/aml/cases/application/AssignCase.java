package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 배정 OPEN → REVIEWING (CASES 명세 API-03, 계약서 A1). ADMIN 이 지정한 활성 조사역에게만 (SCRUM-339 —
 * 조사역 자기 배정 없음). assignee 는 작업자가 아니라 업무 입력이라 body 로 받는다(03_shared §4) — 작업자는 CurrentUser.
 *
 * <p>검사 순서: 역할 403 FORBIDDEN(ADMIN 아님 — SecurityConfig URL 규칙이 먼저 막고, URL 을 안 거치는 호출도 여기서
 * 케이스를 읽기 전에 막는다. CaseQuestionAccess 와 같은 방식) → 케이스 404 → version 409 → 배정 대상(없는 사용자 404
 * USER_NOT_FOUND, 비활성·조사역 아님·비움 409 INVALID_ASSIGNEE) → 상태 409 INVALID_CASE_TRANSITION.
 * 동시에 두 요청이 맡기면 뒤 요청은 커밋 때 409 CONCURRENT_UPDATE.
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
     * @param assigneeLoginId 배정할 조사역 login_id. 비우면 ADMIN 본인이 대상이 되어 409 INVALID_ASSIGNEE.
     */
    @Transactional
    public CaseChangeResult execute(String caseNo, long expectedVersion, String assigneeLoginId) {
        AppUserDetails me = currentUser.require();
        if (!Role.ADMIN.name().equals(me.role())) {
            throw new AccessDeniedException("케이스 배정은 관리자만 할 수 있습니다");   // → 403 FORBIDDEN
        }
        Case c = changes.load(caseNo, expectedVersion);
        String target = assigneeLoginId != null ? assigneeLoginId : me.loginId();
        c.assign(investigators.require(caseNo, target), me.userId());
        return changes.save(c);
    }
}
