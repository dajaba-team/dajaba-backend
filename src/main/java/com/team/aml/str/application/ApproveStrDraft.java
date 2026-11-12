package com.team.aml.str.application;

import com.team.aml.cases.application.ApproveCase;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalGate;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.VerificationResult;
import com.team.aml.str.domain.VerificationResultRepository;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STR 승인 (계약서 P2). 한 트랜잭션 — approvals 승인 한 줄 + 초안 LOCKED + 케이스 APPROVED + 이력 (str.md "승인 한 트랜잭션",
 * flows/03_str "셋 중 하나라도 실패하면 전부 롤백"). 따로 성공하면 "승인됐는데 수정되는" 상태가 생긴다.
 *
 * <p>순서 (바꾸지 않는다):
 * <ol>
 *   <li>승인자 역할 — 아니면 403 NOT_APPROVER (SecurityConfig 가 POST /approvals/** 를 먼저 막고, 여기는 안전장치. str.md 게이트 5번)</li>
 *   <li>초안 읽기 — 없으면 404 DRAFT_NOT_FOUND</li>
 *   <li>cases.ApproveCase — 케이스 잠금 → version 409 → 현재 초안·PENDING_APPROVAL 409 INVALID_CASE_TRANSITION
 *       → 지금·예전 담당이면 409 SELF_APPROVAL (안에서 WasCaseAssignee). 재생성으로 대체된 옛 초안도
 *       여기서 409 로 막힌다 (cases 의 "현재 초안" 검사 — 응답 코드는 #104 SCRUM-223 이 정한다, S6 와 같음). approvals INSERT <b>전에</b> 부른다 —
 *       FK insert 의 KEY SHARE 와 케이스 FOR UPDATE 가 부딪혀 교착이 되지 않게 (ApproveCase 주석 "부르는 순서")</li>
 *   <li>ApprovalGate.requireVerified(요청 번호 없이) — 승인 때도 지금 본문의 최신 검증이 통과인지 같은 기준으로 다시 본다
 *       (str.md 게이트 1·2번 "승인 P2는 번호 없이 null")</li>
 *   <li>본점 의심 정도 저장 → approvals 승인 한 줄(근거 검증·수정본·해시) → 초안 LOCKED (recordHqSuspicion 은 lock 보다 먼저)</li>
 * </ol>
 */
@Service
public class ApproveStrDraft {

    private final StrDraftRepository drafts;
    private final VerificationResultRepository verifications;
    private final ApprovalRepository approvals;
    private final ApproveCase approveCase;
    private final CurrentUser currentUser;
    private final StrCaseChangeReader caseChange;

    public ApproveStrDraft(StrDraftRepository drafts, VerificationResultRepository verifications,
                           ApprovalRepository approvals, ApproveCase approveCase, CurrentUser currentUser,
                           StrCaseChangeReader caseChange) {
        this.drafts = drafts;
        this.verifications = verifications;
        this.approvals = approvals;
        this.approveCase = approveCase;
        this.currentUser = currentUser;
        this.caseChange = caseChange;
    }

    /**
     * @param expectedCaseVersion 화면이 읽은 cases.version (case_version)
     * @param suspicionLevelHq    본점용 의심 정도 1~5 (승인 때만 필수, 기본값 없음)
     */
    @Transactional
    public StrCaseChange execute(String draftNo, long expectedCaseVersion, int suspicionLevelHq) {
        ApprovalGate.requireApprover(Role.valueOf(currentUser.require().role()));

        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));

        approveCase.execute(draft.getCaseId(), draftId, expectedCaseVersion);

        VerificationResult verification = ApprovalGate.requireVerified(draft,
                verifications.findSameBody(draftId, NarrativeHash.of(draft.currentBody())), null);

        draft.recordHqSuspicion(suspicionLevelHq);
        approvals.save(Approval.approved(draft, verification));
        draft.lock();
        drafts.save(draft);

        return caseChange.read(draft.getCaseId());
    }
}
