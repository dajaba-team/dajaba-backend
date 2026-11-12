package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 승인 요청 DRAFTED → PENDING_APPROVAL (CASES 명세 §6.2, API S6).
 * str 승인 요청 유스케이스와 같은 트랜잭션에서만 부른다(MANDATORY — 혼자 부르면 IllegalTransactionStateException).
 *
 * <p>검사 순서: 케이스 잠금 조회(404 CASE_NOT_FOUND) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 * → 현재 초안이 draftId(다른 초안이면 409 DRAFT_SUPERSEDED details.current_draft_no, 현재 초안이 없으면
 * 409 INVALID_CASE_TRANSITION details.draft_no — SCRUM-223) → 상태(409 INVALID_CASE_TRANSITION).
 * 현재 본문이 검증을 통과했는지·보완 반려 뒤 본문이 바뀌었는지는 str ApprovalGate 몫이다(cases 는 본문을 모른다).
 *
 * <p><b>부르는 순서</b> — str 이 approvals 같은 케이스 FK 행을 넣기 <b>전에</b> 부른다(AttachDraftToCase 와 같은 이유:
 * FK insert 의 KEY SHARE 와 여기의 FOR UPDATE 가 부딪혀 교착이 된다). 동시에 온 수정본 저장(TouchCaseForRevision)은
 * 케이스 version 을 올리므로 둘 중 뒤 요청이 409 CONCURRENT_UPDATE (NFR-APR-02, 미결 20).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SubmitCaseForApproval {

    private final CaseChanges changes;
    private final CurrentUser currentUser;

    public SubmitCaseForApproval(CaseChanges changes, CurrentUser currentUser) {
        this.changes = changes;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         승인 요청하는 초안 id. 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면이 들고 온 cases.version(case_version)
     * @return 새 cases.version — str 이 응답의 case_version 에 그대로 싣는다
     */
    public long execute(long caseId, long draftId, long expectedVersion) {
        Case c = changes.lock(caseId, expectedVersion);
        long actorId = currentUser.require().userId();
        changes.requireAssignee(c, actorId);
        c.requireCurrentDraft(draftId, CaseHistoryAction.SUBMIT_FOR_APPROVAL);
        c.submitForApproval(actorId);
        return changes.saveAndGetVersion(c);
    }
}
