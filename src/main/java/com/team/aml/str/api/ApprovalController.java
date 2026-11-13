package com.team.aml.str.api;

import com.team.aml.shared.api.PageResponse;
import com.team.aml.str.application.ApproveStrDraft;
import com.team.aml.str.application.ListPendingApprovals;
import com.team.aml.str.application.RejectStrDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ApprovalController(ListPendingApprovals listPendingApprovals, ApproveStrDraft approveStrDraft,
                              RejectStrDraft rejectStrDraft) {
        this.listPendingApprovals = listPendingApprovals;
        this.approveStrDraft = approveStrDraft;
        this.rejectStrDraft = rejectStrDraft;
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
                    + "지금 담당이거나 예전에 담당했던 사람이면 409 SELF_APPROVAL, case_version 다르면 409 CONCURRENT_UPDATE. "
                    + "초안은 ACTIVE 그대로. 응답은 케이스 공통 변경 응답(rejection_reason_code 포함)")
    @PostMapping("/{draftNo}/reject")
    public StrCaseChangeResponse reject(@PathVariable String draftNo,
                                        @Valid @RequestBody RejectStrDraftRequest request) {
        return StrCaseChangeResponse.from(rejectStrDraft.execute(draftNo, request.caseVersion(),
                request.reasonCode(), request.comment()));
    }
}
