package com.team.aml.str.api;

import com.team.aml.shared.api.PageResponse;
import com.team.aml.str.application.ApproveStrDraft;
import com.team.aml.str.application.ListPendingApprovals;
import com.team.aml.str.application.RegenerateStrReportPdf;
import com.team.aml.str.application.RejectStrDraft;
import com.team.aml.str.application.ReportStrDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 승인함 (계약서 5.5 P1~). 역할은 SecurityConfig 가 먼저 막는다 — GET 은 ADMIN·APPROVER, POST 는 APPROVER.
 * 경로 번호는 draft_no (계약서 2.2). api 는 str.application 만 부른다(ArchUnit).
 */
@Tag(name = "approvals")
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ListPendingApprovals listPendingApprovals;
    private final ApproveStrDraft approveStrDraft;
    private final RejectStrDraft rejectStrDraft;
    private final ReportStrDraft reportStrDraft;
    private final RegenerateStrReportPdf regenerateStrReportPdf;

    public ApprovalController(ListPendingApprovals listPendingApprovals, ApproveStrDraft approveStrDraft,
                              RejectStrDraft rejectStrDraft,
                              ReportStrDraft reportStrDraft, RegenerateStrReportPdf regenerateStrReportPdf) {
        this.listPendingApprovals = listPendingApprovals;
        this.approveStrDraft = approveStrDraft;
        this.rejectStrDraft = rejectStrDraft;
        this.reportStrDraft = reportStrDraft;
        this.regenerateStrReportPdf = regenerateStrReportPdf;
    }

    @Operation(summary = "승인 대기 목록",
            description = "승인자·관리자(조회만, 그 밖 403). 요청 오래된 순 고정, page 0부터·size ≤ 100. 본문 없음 — 본문은 S2. "
                    + "줄마다 was_assignee(내가 담당했던 케이스)·available_actions(승인자이고 담당한 적 없으면 APPROVE·REJECT)")
    @GetMapping
    public PageResponse<PendingApprovalResponse> list(@Valid @ParameterObject @ModelAttribute ApprovalListQuery query) {
        return PageResponse.from(listPendingApprovals.execute(query.pageOrDefault(), query.sizeOrDefault())
                .map(PendingApprovalResponse::from));
    }

    @Operation(summary = "STR 승인",
            description = "승인자만(그 밖 403). 케이스 PENDING_APPROVAL·현재 초안이어야 한다(409 INVALID_CASE_TRANSITION). "
                    + "지금 담당이거나 예전에 담당했던 사람이면 409 SELF_APPROVAL, case_version 다르면 409 CONCURRENT_UPDATE, "
                    + "지금 본문의 최신 검증이 PASSED 가 아니면 409 DRAFT_NOT_VERIFIED. 본점 의심 정도 1~5 필수(400). "
                    + "승인 기록·초안 LOCKED·케이스 APPROVED 가 한 번에. 응답은 케이스 공통 변경 응답")
    @PostMapping("/{draftNo}/approve")
    public StrCaseChangeResponse approve(@PathVariable String draftNo,
                                         @Valid @RequestBody ApproveStrDraftRequest request) {
        return StrCaseChangeResponse.from(
                approveStrDraft.execute(draftNo, request.caseVersion(), request.suspicionLevelHq()));
    }

    @Operation(summary = "STR 반려 (보완 요청·보고 안 함)",
            description = "승인자만(그 밖 403). reason_code NEED_REVISION(보완 요청) 또는 NOT_REPORTABLE(보고 안 함), "
                    + "comment 1~2,000자 둘 다 필수(400). 케이스 PENDING_APPROVAL·현재 초안이어야 한다(409 INVALID_CASE_TRANSITION). "
                    + "재생성으로 대체된 옛 초안이면 409 DRAFT_SUPERSEDED(details.current_draft_no). "
                    + "지금 담당이거나 예전에 담당했던 사람이면 409 SELF_APPROVAL, case_version 다르면 409 CONCURRENT_UPDATE. "
                    + "초안은 ACTIVE 그대로. 응답은 케이스 공통 변경 응답(rejection_reason_code 포함)")
    @PostMapping("/{draftNo}/reject")
    public StrCaseChangeResponse reject(@PathVariable String draftNo,
                                        @Valid @RequestBody RejectStrDraftRequest request) {
        return StrCaseChangeResponse.from(rejectStrDraft.execute(draftNo, request.caseVersion(),
                request.reasonCode(), request.comment()));
    }

    @Operation(summary = "STR 모의 제출",
            description = "이 초안을 승인한 사람만(역할이 승인자가 아니면 403 NOT_APPROVER, 다른 승인자는 403 NOT_DRAFT_APPROVER). 실제 기관 전송 없음 — 승인된 보고서를 제출됨으로 기록한다. "
                    + "케이스 APPROVED·현재 초안이어야 한다(아니면 409 INVALID_CASE_TRANSITION, 대체된 초안은 409 DRAFT_SUPERSEDED). "
                    + "이미 제출했으면 409 INVALID_CASE_TRANSITION, case_version 다르면 409 CONCURRENT_UPDATE. "
                    + "응답: case_status REPORTED·문서번호·마감·제출 시각·늦음(late)·pdf_status PENDING")
    @PostMapping("/{draftNo}/report")
    public ReportStrDraftResponse report(@PathVariable String draftNo,
                                         @Valid @RequestBody ReportStrDraftRequest request) {
        return ReportStrDraftResponse.from(reportStrDraft.execute(draftNo, request.caseVersion()));
    }

    @Operation(summary = "확정본 PDF 다시 만들기 (P5)",
            description = "이 초안을 승인한 사람만(역할이 승인자가 아니면 403 NOT_APPROVER, 다른 승인자는 403 NOT_DRAFT_APPROVER). "
                    + "케이스 REPORTED·현재 초안이어야 한다(아니면 409 INVALID_CASE_TRANSITION, 대체된 초안은 409 DRAFT_SUPERSEDED). "
                    + "PDF 가 FAILED 이거나 PENDING 이 오래됐을 때만(aml.str.pdf.stale-pending-after, 기본 10분 — 만드는 도중 서버가 죽은 경우) — "
                    + "아니면 409 INVALID_CASE_TRANSITION(details.action REGENERATE_PDF·draft_no·pdf_status). "
                    + "승인 때 고정한 스냅샷으로 다시 만든다(최신 정보로 다시 조립하지 않음). 본문 없음. "
                    + "응답 202 {pdf_status: PENDING} — 만들기는 뒤에서, 화면은 S8 을 3초마다 다시 본다")
    @PostMapping("/{draftNo}/report/pdf")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegenerateReportPdfResponse regeneratePdf(@PathVariable String draftNo) {
        return RegenerateReportPdfResponse.from(regenerateStrReportPdf.execute(draftNo));
    }
}
