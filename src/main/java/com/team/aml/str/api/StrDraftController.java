package com.team.aml.str.api;

import com.team.aml.shared.ai.AiDtos;
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

    public StrDraftController(RequestStrDraft requestStrDraft) {
        this.requestStrDraft = requestStrDraft;
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
}
