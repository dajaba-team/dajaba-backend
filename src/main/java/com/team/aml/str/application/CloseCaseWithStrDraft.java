package com.team.aml.str.application;

import com.team.aml.cases.application.CloseCaseWithDraft;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초안이 있는 케이스 종결 (계약서 A6 POST /cases/{case_no}/close-with-draft, SCRUM-186). 컨트롤러·유스케이스는 str (str.md API 9번).
 * 한 트랜잭션 — 케이스 CLOSED·CLOSE 이력(cases CloseCaseWithDraft, #111) + 현재 초안 SUPERSEDED. 따로 성공하면
 * "닫힌 케이스에 사용 중 초안"이 남는다.
 *
 * <p>순서 (바꾸지 않는다):
 * <ol>
 *   <li>케이스 읽기 — 없으면 404 CASE_NOT_FOUND. 현재 초안이 없으면 409 INVALID_CASE_TRANSITION
 *       (초안 없는 종결은 A5 /close — 화면은 available_actions 의 CLOSE·CLOSE_WITH_DRAFT 를 따라 고른다)</li>
 *   <li>cases.CloseCaseWithDraft — 케이스 잠금 → version 409 → 담당 403 NOT_ASSIGNEE → 현재 초안(다르면 409 DRAFT_SUPERSEDED)
 *       → 상태(DRAFTED·보고 안 함 반려만, 아니면 409 INVALID_CASE_TRANSITION) → 사유 COMMENT_REQUIRED.
 *       str_drafts 를 바꾸기 <b>전에</b> 부른다 (CloseCaseWithDraft 주석 "부르는 순서")</li>
 *   <li>현재 초안 SUPERSEDED — StrDraft.supersede (사용 중이 아니면 409 DRAFT_LOCKED: 잠긴 초안은 케이스가 APPROVED 라 ②에서 먼저 막혀
 *       보통 안 걸리는 안전장치)</li>
 * </ol>
 * ①에서 잠그지 않고 읽은 케이스가 그 사이 바뀌었으면 ②의 저장(@Version)이 409 CONCURRENT_UPDATE 로 막는다 — cases A5(CloseCase)와 같은 방식.
 * 종결 뒤에도 cases.current_draft_id 는 남는다(그 초안은 SUPERSEDED, 이력·열람용).
 */
@Service
public class CloseCaseWithStrDraft {

    private final CaseRepository cases;
    private final CloseCaseWithDraft closeCase;
    private final StrDraftRepository drafts;
    private final StrCaseChangeReader caseChange;

    public CloseCaseWithStrDraft(CaseRepository cases, CloseCaseWithDraft closeCase, StrDraftRepository drafts,
                                 StrCaseChangeReader caseChange) {
        this.cases = cases;
        this.closeCase = closeCase;
        this.drafts = drafts;
        this.caseChange = caseChange;
    }

    /**
     * @param closeReason FALSE_POSITIVE·DUPLICATE·NO_ACTION — 다른 값은 api 가 400 으로 먼저 막는다.
     *                    문자열로 받는 이유: str api 는 cases 의 CloseReason 을 볼 수 없다(ArchUnit). P3 reason_code 와 같은 방식
     * @param comment     설명 1~2,000자
     * @param version     화면이 읽은 cases.version (계약서 A6 본문 이름은 case_version 이 아니라 version — A5 와 같은 본문)
     */
    @Transactional
    public StrCaseChange execute(String caseNo, String closeReason, String comment, long version) {
        Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
        Long draftId = c.getCurrentDraftId();
        if (draftId == null) {
            throw new InvalidCaseTransitionException(c.getStatus(), CaseStatus.CLOSED, c.getRejectionReasonCode());
        }

        closeCase.execute(c.getId(), draftId, version, CloseReason.valueOf(closeReason), comment);

        StrDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new IllegalStateException("케이스의 현재 초안이 없다: " + caseNo + ", draft " + draftId));
        draft.supersede();
        drafts.save(draft);

        return caseChange.read(c.getId());
    }
}
