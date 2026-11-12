package com.team.aml.str.application;

import com.team.aml.cases.application.SubmitCaseForApproval;
import com.team.aml.str.domain.ApprovalGate;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.VerificationResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * STR 승인 요청 (계약서 S6, flows/03_str "승인 요청"). 한 트랜잭션 — 케이스 PENDING_APPROVAL + 의심 유형·지점 정도 저장.
 *
 * <p>순서 (바꾸지 않는다):
 * <ol>
 *   <li>초안 읽기 — 없으면 404 DRAFT_NOT_FOUND</li>
 *   <li>cases.SubmitCaseForApproval — 케이스 잠금 → version 409 CONCURRENT_UPDATE → 담당 403 NOT_ASSIGNEE
 *       → 현재 초안·DRAFTED 아니면 409 INVALID_CASE_TRANSITION. 케이스를 <b>먼저 잠가서</b> 같은 케이스의 본문 수정(S3)과
 *       동시에 와도 한쪽은 409 (str.md "잠근 뒤 재확인", 미결 20 — ReviseStrDraft 와 같은 순서)</li>
 *   <li>ApprovalGate.requireSubmittable — 사용 중 초안(409 DRAFT_LOCKED) → 보완 요청 뒤 같은 본문(409 NARRATIVE_UNCHANGED)
 *       → 지금 본문의 최신 검증이 통과·요청한 verification_id 와 같음(409 DRAFT_NOT_VERIFIED) (str.md 게이트 1~4번)</li>
 *   <li>의심 유형·지점 의심 정도 저장 — 편집 중 저장 API 가 없고 이 요청에만 실려 온다 (계약서 S6 토글, 11/4)</li>
 * </ol>
 * 어디서 실패하든 전부 롤백 — 케이스는 DRAFTED, 의심 유형은 예전 값 그대로.
 */
@Service
public class SubmitStrDraft {

    private final StrDraftRepository drafts;
    private final VerificationResultRepository verifications;
    private final ApprovalRepository approvals;
    private final SubmitCaseForApproval submitCase;
    private final StrCaseChangeReader caseChange;

    public SubmitStrDraft(StrDraftRepository drafts, VerificationResultRepository verifications,
                          ApprovalRepository approvals, SubmitCaseForApproval submitCase,
                          StrCaseChangeReader caseChange) {
        this.drafts = drafts;
        this.verifications = verifications;
        this.approvals = approvals;
        this.submitCase = submitCase;
        this.caseChange = caseChange;
    }

    /**
     * @param draftNo              DRF-000123
     * @param expectedCaseVersion  화면이 읽은 cases.version (case_version)
     * @param verificationId       화면이 본 검증 — 지금 본문의 최신 검증이어야 한다
     * @param suspicionTypes       의심 유형(서식 VI) 1개 이상
     * @param suspicionLevelBranch 지점용 의심 정도 1~5
     */
    @Transactional
    public StrCaseChange execute(String draftNo, long expectedCaseVersion, String verificationId,
                                 List<String> suspicionTypes, int suspicionLevelBranch) {
        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));

        submitCase.execute(draft.getCaseId(), draftId, expectedCaseVersion);

        String hash = NarrativeHash.of(draft.currentBody());
        ApprovalGate.requireSubmittable(draft,
                verifications.findSameBody(draftId, hash),
                verificationId,
                approvals.findLatestRejection(draftId).orElse(null));

        draft.recordBranchSuspicion(suspicionTypes, suspicionLevelBranch);
        drafts.save(draft);

        return caseChange.read(draft.getCaseId());
    }
}
