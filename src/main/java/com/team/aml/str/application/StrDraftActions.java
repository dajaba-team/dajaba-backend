package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.user.domain.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S2 available_actions — 지금 이 사용자가 STR 화면에서 누를 수 있는 버튼 (계약서 S2 토글 Q16, 4절 상태별 버튼).
 * 화면은 이 목록만 보고 버튼을 켜고, 변경 API 는 눌렀을 때 다시 검사한다(편의 값 — CaseAction 과 같은 원칙).
 *
 * <p>상태 판단을 str 이 새로 하지 않는다 — 조사역 버튼은 cases {@link CaseAction#availableFor}(= CaseStatus.allows 한 곳)에서
 * 옮겨 온다. 케이스 상세(C2) 버튼과 STR 화면 버튼이 따로 놀지 않게 (SCRUM-171). 프론트 strRules.strActionsFor 의 임시 계산과 같은 규칙.
 * <ul>
 *   <li>조사역(담당): GENERATE_DRAFT 가 되면 REVISE·REGENERATE, 그중 케이스 DRAFTED 면 VERIFY·SUBMIT 도 (S5·S6 은 DRAFTED 만).
 *       CLOSE_WITH_DRAFT 는 C2 와 같은 값</li>
 *   <li>승인자: 케이스 PENDING_APPROVAL 이고 지금·예전 담당이 아니면 APPROVE·REJECT (미결 32 — ApproveCase·RejectCase 와 같은 판단).
 *       케이스 APPROVED·초안 LOCKED 이고 이 초안을 승인한 사람이면 REPORT (모의 제출 P4 — ReportStrDraft 의 403 NOT_DRAFT_APPROVER 와 같은 판단)</li>
 *   <li>PDF: 케이스 APPROVED·초안 LOCKED 면 승인자(누구나)와 담당 조사역에게 PREVIEW_PDF — 판단은 S7 403 검사와 같은
 *       {@link StrReportPdfAccess#canRead} 를 그대로 부른다(규칙 한 곳). 케이스 REPORTED·초안 LOCKED 면 같은 사람에게 DOWNLOAD_PDF
 *       (S8 — PDF 가 아직 PENDING 이어도 켠다. 화면이 409 REPORT_PDF_NOT_READY 로 "만드는 중"을 보여 준다).
 *       그중 {@link StrReportPdfAccess#canRegenerate} 면 REGENERATE_PDF 도 (P5 — PDF FAILED 또는 오래된 PENDING, 이 초안을 승인한 사람)</li>
 *   <li>그 밖(관리자·비담당·대체된 초안·다른 상태): []</li>
 * </ul>
 * SUBMIT 은 "상태상 가능"만 뜻한다 — 지금 본문 검증 통과 여부는 화면이 latest_verification.is_current·passed 로 함께 보고
 * 꺼진 이유 문구를 고른다(계약서 S6 켜는 조건).
 */
final class StrDraftActions {

    static final String REVISE = "REVISE";
    static final String VERIFY = "VERIFY";
    static final String SUBMIT = "SUBMIT";
    static final String REGENERATE = "REGENERATE";
    static final String CLOSE_WITH_DRAFT = "CLOSE_WITH_DRAFT";
    static final String APPROVE = "APPROVE";
    static final String REJECT = "REJECT";
    static final String REPORT = "REPORT";
    static final String PREVIEW_PDF = "PREVIEW_PDF";
    static final String DOWNLOAD_PDF = "DOWNLOAD_PDF";
    static final String REGENERATE_PDF = "REGENERATE_PDF";
    /** 참고 사례 찾기(S9) — 이 목록 계산 밖에서 GetStrDraft 가 설정·본문 열람으로 붙인다(상태와 무관). */
    static final String SEARCH_REFERENCES = "SEARCH_REFERENCES";

    private StrDraftActions() {}

    /**
     * @param draftId           보고 있는 초안 (DB id) — 케이스의 현재 초안이 아니면(재생성으로 대체) 버튼 없음
     * @param draftStatus       조사역·승인 버튼은 ACTIVE 만, 모의 제출(REPORT)은 LOCKED 만
     * @param viewerWasAssignee   보는 사람이 이 케이스를 지금·예전에 담당했나 (cases WasCaseAssignee). 승인자일 때만 쓴다
     * @param viewerApprovedDraft 보는 사람이 이 초안을 승인한 사람인가 (StrReport.isApprovedBy). REPORT 에만 쓴다
     * @param viewerCanRegenerate StrReportPdfAccess.canRegenerate 결과. REGENERATE_PDF 에만 쓴다
     */
    static List<String> of(long draftId, StrDraftStatus draftStatus, Case c, long viewerId, Role viewerRole,
                           boolean viewerWasAssignee, boolean viewerApprovedDraft, boolean viewerCanRegenerate) {
        if (!Objects.equals(c.getCurrentDraftId(), draftId)) {
            return List.of();                                         // 재생성으로 대체된 옛 초안
        }
        if (draftStatus == StrDraftStatus.LOCKED && c.getStatus() == CaseStatus.APPROVED) {
            List<String> actions = new ArrayList<>(2);
            if (viewerRole == Role.APPROVER && viewerApprovedDraft) {
                actions.add(REPORT);                                  // 모의 제출(P4) — 이 초안을 승인한 사람만(11/17)
            }
            // 미리보기(S7) — 누가 볼 수 있는지는 PreviewStrReportPdf 의 403 검사와 같은 한 곳(StrReportPdfAccess.canRead)에서 (#147 리뷰)
            if (StrReportPdfAccess.canRead(viewerRole, c, viewerId)) {
                actions.add(PREVIEW_PDF);
            }
            return List.copyOf(actions);
        }
        if (draftStatus == StrDraftStatus.LOCKED && c.getStatus() == CaseStatus.REPORTED) {
            // 확정본(S8) — S8 의 403 검사와 같은 한 곳(StrReportPdfAccess.canRead)
            if (!StrReportPdfAccess.canRead(viewerRole, c, viewerId)) {
                return List.of();
            }
            return viewerCanRegenerate
                    ? List.of(DOWNLOAD_PDF, REGENERATE_PDF)           // 다시 만들기(P5) — 서버 검사와 같은 한 곳(canRegenerate)
                    : List.of(DOWNLOAD_PDF);
        }
        if (viewerRole == Role.APPROVER) {
            if (draftStatus == StrDraftStatus.ACTIVE && c.getStatus() == CaseStatus.PENDING_APPROVAL && !viewerWasAssignee) {
                return List.of(APPROVE, REJECT);
            }
            return List.of();
        }
        if (draftStatus != StrDraftStatus.ACTIVE) {
            return List.of();                                         // 조사역 버튼은 사용 중 초안에만
        }
        if (viewerRole != Role.INVESTIGATOR) {
            return List.of();
        }
        List<CaseAction> caseActions = CaseAction.availableFor(c, viewerId, viewerRole);   // 비담당이면 담당 동작이 빠져 온다
        List<String> actions = new ArrayList<>();
        if (caseActions.contains(CaseAction.GENERATE_DRAFT)) {
            actions.add(REVISE);
            if (c.getStatus() == CaseStatus.DRAFTED) {
                actions.add(VERIFY);
                actions.add(SUBMIT);
            }
            actions.add(REGENERATE);
        }
        if (caseActions.contains(CaseAction.CLOSE_WITH_DRAFT)) {
            actions.add(CLOSE_WITH_DRAFT);
        }
        return List.copyOf(actions);
    }
}
