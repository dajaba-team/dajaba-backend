package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 초안이 있는 케이스 종결 → CLOSED (CASES 명세 §2.2·§6.2·SEQ-09, API A6, SCRUM-235).
 * str 종결 유스케이스(POST /cases/{case_no}/close-with-draft, SCRUM-186)와 같은 트랜잭션에서만 부른다(MANDATORY) —
 * 케이스 CLOSED·CLOSE 이력과 str 의 현재 초안 SUPERSEDED 가 함께 성공하거나 롤백된다.
 * 초안이 없는 종결은 화면용 {@link CloseCase}(API-06)다 — 이름이 겹치지 않게 WithDraft 를 붙였다.
 *
 * <p>검사 순서: 케이스 잠금 조회(404 CASE_NOT_FOUND) → version(409 CONCURRENT_UPDATE) → 담당자(403 NOT_ASSIGNEE)
 * → 현재 초안이 draftId(다른 초안이면 409 DRAFT_SUPERSEDED details.current_draft_no, 현재 초안이 없으면
 * 409 INVALID_CASE_TRANSITION details.draft_no — SCRUM-223) → 상태(409 INVALID_CASE_TRANSITION)
 * → 사유(COMMENT_REQUIRED — 형식 400 은 str 요청 DTO 가 먼저 막는다).
 * 닫을 수 있는 상태는 DRAFTED 와 보고 안 함 반려(REJECTED+NOT_REPORTABLE). 보완 반려(NEED_REVISION)·승인 대기 이후는 409.
 *
 * <p>종결 뒤에도 cases.current_draft_id 는 남는다(V006 cases_current_draft_ck — CLOSED 는 초안 있음·없음 둘 다 허용).
 * 반려 코드는 지우고, 과거 코드는 REJECT 이력과 approvals 에 남는다.
 *
 * <p>str 이 str_drafts 를 SUPERSEDED 로 바꾸기 <b>전에</b> 부른다 — 다른 공개 기능과 같이 케이스를 먼저 잠근다
 * (SubmitCaseForApproval 주석의 "부르는 순서").
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CloseCaseWithDraft {

    private final CaseChanges changes;
    private final CurrentUser currentUser;

    public CloseCaseWithDraft(CaseChanges changes, CurrentUser currentUser) {
        this.changes = changes;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         케이스의 현재 초안 id — str 이 이어서 SUPERSEDED 로 바꾼다
     * @param expectedVersion 화면이 들고 온 cases.version(요청 본문 version)
     * @param closeReason     종결 사유(FALSE_POSITIVE·DUPLICATE·NO_ACTION)
     * @param comment         설명(공백 아님). 길이(1~2,000자)는 str 요청 DTO 가 본다
     * @return 새 cases.version — str 이 응답에 그대로 싣는다
     */
    public long execute(long caseId, long draftId, long expectedVersion, CloseReason closeReason, String comment) {
        Case c = changes.lock(caseId, expectedVersion);
        long actorId = currentUser.require().userId();
        changes.requireAssignee(c, actorId);
        c.requireCurrentDraft(draftId, CaseHistoryAction.CLOSE);
        c.close(actorId, closeReason, comment);
        return changes.saveAndGetVersion(c);
    }
}
