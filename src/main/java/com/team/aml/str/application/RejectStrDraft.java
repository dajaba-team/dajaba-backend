package com.team.aml.str.application;

import com.team.aml.cases.application.RejectCase;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalGate;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STR 반려 (계약서 P3, 안건 24). 버튼은 둘([보완 요청]·[보고 안 함])이고 API 는 하나 — reason_code 만 다르다.
 * 한 트랜잭션 — approvals 반려 한 줄(반려 당시 본문 해시 포함) + 케이스 REJECTED·반려 코드 + 이력.
 * 반려 코드는 approvals(이력)와 cases(상태 규칙) 두 곳에 같이 남는다 (str.md "반려 두 갈래").
 *
 * <p>순서 (바꾸지 않는다):
 * <ol>
 *   <li>승인자 역할 — 아니면 403 NOT_APPROVER (ApproveStrDraft 와 같음)</li>
 *   <li>초안 읽기 — 없으면 404 DRAFT_NOT_FOUND</li>
 *   <li>cases.RejectCase — 케이스 잠금 → version 409 → 현재 초안·PENDING_APPROVAL 409 → 지금·예전 담당이면 409 SELF_APPROVAL
 *       → 사유 필수. 재생성으로 대체된 옛 초안도
 *       여기서 409 로 막힌다 (cases 의 "현재 초안" 검사 — 응답 코드는 #104 SCRUM-223 이 정한다, S6·P2 와 같음). approvals INSERT <b>전에</b> 부른다 (RejectCase 주석 "부르는 순서")</li>
 *   <li>초안이 사용 중인지(409 DRAFT_LOCKED) → approvals 반려 한 줄. 초안은 ACTIVE 그대로 — 보완 요청이면 조사역이 고쳐 쓰고,
 *       보고 안 함이면 초안 있는 종료(A6)가 SUPERSEDED 로 바꾼다</li>
 * </ol>
 * 반려 당시 해시는 다음 승인 요청 때 ApprovalGate 가 NARRATIVE_UNCHANGED 를 판단하는 기준이다 (게이트 4번).
 */
@Service
public class RejectStrDraft {

    private final StrDraftRepository drafts;
    private final ApprovalRepository approvals;
    private final RejectCase rejectCase;
    private final CurrentUser currentUser;
    private final StrCaseChangeReader caseChange;

    public RejectStrDraft(StrDraftRepository drafts, ApprovalRepository approvals, RejectCase rejectCase,
                          CurrentUser currentUser, StrCaseChangeReader caseChange) {
        this.drafts = drafts;
        this.approvals = approvals;
        this.rejectCase = rejectCase;
        this.currentUser = currentUser;
        this.caseChange = caseChange;
    }

    /**
     * @param reasonCode NEED_REVISION(보완 요청) 또는 NOT_REPORTABLE(보고 안 함) — 다른 값은 api 가 400 으로 먼저 막는다.
     *                   문자열로 받는 이유: str api 는 cases 의 RejectionReasonCode 를 볼 수 없다(ArchUnit)
     * @param comment    반려 사유 1~2,000자
     */
    @Transactional
    public StrCaseChange execute(String draftNo, long expectedCaseVersion, String reasonCode, String comment) {
        ApprovalGate.requireApprover(Role.valueOf(currentUser.require().role()));
        RejectionReasonCode code = RejectionReasonCode.valueOf(reasonCode);

        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));

        rejectCase.execute(draft.getCaseId(), draftId, expectedCaseVersion, code, comment);

        draft.requireActive();
        approvals.save(Approval.rejected(draft, code, comment));

        return caseChange.read(draft.getCaseId());
    }
}
