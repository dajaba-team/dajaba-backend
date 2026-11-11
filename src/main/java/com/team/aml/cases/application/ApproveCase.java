package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 승인 PENDING_APPROVAL → APPROVED (CASES 명세 §6.2, API P2).
 * str ApproveStr 와 같은 트랜잭션에서만 부른다(MANDATORY) — 승인 기록·초안 LOCKED·케이스 APPROVED·이력이 함께 성공하거나 롤백된다.
 *
 * <p>검사 순서: 케이스 잠금 조회(404 CASE_NOT_FOUND) → version(409 CONCURRENT_UPDATE)
 * → 현재 초안이 draftId(409 INVALID_CASE_TRANSITION, details.current_draft_id) → 상태(409 INVALID_CASE_TRANSITION)
 * → 승인자가 지금·예전 담당(409 SELF_APPROVAL, 미결 32 — 여기서 WasCaseAssignee 로 이력을 본다).
 * 승인자 역할(APPROVER)은 SecurityConfig 의 /approvals/** 와 str ApprovalGate.requireApprover 가 본다.
 *
 * <p>str 이 approvals 행을 넣기 <b>전에</b> 부른다(SubmitCaseForApproval 주석의 "부르는 순서").
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ApproveCase {

    private final CaseChanges changes;
    private final WasCaseAssignee wasCaseAssignee;
    private final CurrentUser currentUser;

    public ApproveCase(CaseChanges changes, WasCaseAssignee wasCaseAssignee, CurrentUser currentUser) {
        this.changes = changes;
        this.wasCaseAssignee = wasCaseAssignee;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         승인하는 초안 id. 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면이 들고 온 cases.version(case_version)
     * @return 새 cases.version — str 이 응답의 case_version 에 그대로 싣는다
     */
    public long execute(long caseId, long draftId, long expectedVersion) {
        Case c = changes.lock(caseId, expectedVersion);
        long approverId = currentUser.require().userId();
        c.requireCurrentDraft(draftId, CaseHistoryAction.APPROVE);
        c.approve(approverId, wasCaseAssignee.execute(caseId, approverId));
        return changes.saveAndGetVersion(c);
    }
}
