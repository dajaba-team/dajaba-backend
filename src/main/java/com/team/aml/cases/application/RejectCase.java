package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 반려 PENDING_APPROVAL → REJECTED (CASES 명세 §6.2, API P3, 안건 24).
 * str RejectStr 와 같은 트랜잭션에서만 부른다(MANDATORY) — 반려 코드는 approvals(이력)와 cases(상태 규칙) 두 곳에 같이 남는다.
 *
 * <p>검사 순서: 케이스 잠금 조회(404 CASE_NOT_FOUND) → version(409 CONCURRENT_UPDATE)
 * → 현재 초안이 draftId(409 INVALID_CASE_TRANSITION, details.current_draft_id) → 상태(409 INVALID_CASE_TRANSITION)
 * → 승인자가 지금·예전 담당(409 SELF_APPROVAL) → 사유(CommentRequiredException). ApproveCase 와 같고 사유 검사만 더 있다.
 * 반려 뒤 갈 수 있는 곳은 코드가 정한다 — NEED_REVISION 은 수정본·새 초안, NOT_REPORTABLE 은 종결만.
 *
 * <p>str 이 approvals 행을 넣기 <b>전에</b> 부른다(SubmitCaseForApproval 주석의 "부르는 순서").
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RejectCase {

    private final CaseChanges changes;
    private final WasCaseAssignee wasCaseAssignee;
    private final CurrentUser currentUser;

    public RejectCase(CaseChanges changes, WasCaseAssignee wasCaseAssignee, CurrentUser currentUser) {
        this.changes = changes;
        this.wasCaseAssignee = wasCaseAssignee;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         반려하는 초안 id. 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면이 들고 온 cases.version(case_version)
     * @param code            NEED_REVISION(보완 요청) 또는 NOT_REPORTABLE(보고 안 함)
     * @param comment         반려 사유 — 비어 있으면 안 된다
     * @return 새 cases.version — str 이 응답의 case_version 에 그대로 싣는다
     */
    public long execute(long caseId, long draftId, long expectedVersion, RejectionReasonCode code, String comment) {
        Case c = changes.lock(caseId, expectedVersion);
        long approverId = currentUser.require().userId();
        c.requireCurrentDraft(draftId, CaseHistoryAction.REJECT);
        c.reject(approverId, wasCaseAssignee.execute(caseId, approverId), code, comment);
        return changes.saveAndGetVersion(c);
    }
}
