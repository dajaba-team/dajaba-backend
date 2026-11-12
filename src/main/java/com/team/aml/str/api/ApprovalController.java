package com.team.aml.str.api;

import com.team.aml.shared.api.PageResponse;
import com.team.aml.str.application.ListPendingApprovals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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

    public ApprovalController(ListPendingApprovals listPendingApprovals) {
        this.listPendingApprovals = listPendingApprovals;
    }

    @Operation(summary = "승인 대기 목록",
            description = "승인자·관리자(조회만, 그 밖 403). 요청 오래된 순 고정, page 0부터·size ≤ 100. 본문 없음 — 본문은 S2. "
                    + "줄마다 was_assignee(내가 담당했던 케이스)·available_actions(승인자이고 담당한 적 없으면 APPROVE·REJECT)")
    @GetMapping
    public PageResponse<PendingApprovalResponse> list(@Valid @ParameterObject @ModelAttribute ApprovalListQuery query) {
        return PageResponse.from(listPendingApprovals.execute(query.pageOrDefault(), query.sizeOrDefault())
                .map(PendingApprovalResponse::from));
    }
}
