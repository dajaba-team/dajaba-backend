package com.team.aml.str.api;

import com.team.aml.str.application.GetStrDraft;
import com.team.aml.str.application.RequestStrDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * STR 초안 (계약서 S1~). 담당자·상태 검사는 유스케이스가 한다 — 컨트롤러는 경로·본문만.
 * api 는 다른 기능(cases 등)을 직접 부르지 않는다(ArchUnit) — str.application 만.
 */
@Tag(name = "str")
@RestController
@RequestMapping("/api/v1")
public class StrDraftController {
    private final RequestStrDraft requestStrDraft;
    private final GetStrDraft getStrDraft;

    public StrDraftController(RequestStrDraft requestStrDraft, GetStrDraft getStrDraft) {
        this.requestStrDraft = requestStrDraft;
        this.getStrDraft = getStrDraft;
    }

    @Operation(summary = "STR 초안 생성·재생성 (최대 90초)",
            description = "담당만(403 NOT_ASSIGNEE), ESCALATED·DRAFTED·보완 요청 반려에서만(409 INVALID_CASE_TRANSITION). "
                    + "만드는 중이면 409 DRAFT_IN_PROGRESS, version 다르면 409 CONCURRENT_UPDATE, AI 실패는 502")
    @PostMapping("/cases/{caseNo}/str-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public StrDraftCreatedResponse create(@PathVariable String caseNo,
                                          @Valid @RequestBody CreateStrDraftRequest request) {
        return StrDraftCreatedResponse.from(requestStrDraft.execute(caseNo, request.caseVersion()));
    }

    @Operation(
            summary = "STR 초안 상세 조회",
            description = "담당 조사역·책임자는 전체 조회, ADMIN은 상태·생성 시각만 조회합니다. "
                    + "담당자가 아닌 조사역은 403 NOT_ASSIGNEE입니다."
    )
    @GetMapping("/str-drafts/{draftNo}")
    public StrDraftDetailResponse get(
            @PathVariable String draftNo
    ) {
        return StrDraftDetailResponse.from(
                getStrDraft.execute(draftNo)
        );
    }
}
