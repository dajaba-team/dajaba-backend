package com.team.aml.str.api;

import com.team.aml.str.application.GetStrDraft;
import com.team.aml.str.application.ListStrRevisions;
import com.team.aml.str.application.RequestStrDraft;
import com.team.aml.str.application.ReviseStrDraft;
import com.team.aml.str.application.VerifyStrDraft;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final ReviseStrDraft reviseStrDraft;
    private final ListStrRevisions listStrRevisions;
    private final VerifyStrDraft verifyStrDraft;

    public StrDraftController(RequestStrDraft requestStrDraft, GetStrDraft getStrDraft,
                              ReviseStrDraft reviseStrDraft, ListStrRevisions listStrRevisions,
                              VerifyStrDraft verifyStrDraft) {
        this.requestStrDraft = requestStrDraft;
        this.getStrDraft = getStrDraft;
        this.reviseStrDraft = reviseStrDraft;
        this.listStrRevisions = listStrRevisions;
        this.verifyStrDraft = verifyStrDraft;
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
            description = "담당 조사역·승인자는 access FULL(본문 포함). 담당이 아닌 조사역·ADMIN은 200 access META_ONLY "
                    + "(draft_no·status·produced_at만 값, 나머지 키는 null). 없는 번호·형식 오류는 404 DRAFT_NOT_FOUND."
    )
    @GetMapping("/str-drafts/{draftNo}")
    public StrDraftDetailResponse get(
            @PathVariable String draftNo
    ) {
        return StrDraftDetailResponse.from(
                getStrDraft.execute(draftNo)
        );
    }

    @Operation(summary = "STR 본문 수정 저장 (고친 단락만)",
            description = "담당만(403 NOT_ASSIGNEE). 단락 문장 비었거나 NFC 5,000자 초과·같은 단락 두 번은 400 INVALID_REQUEST. "
                    + "case_version 다르면 409 CONCURRENT_UPDATE, DRAFTED·보완 요청 반려가 아니면 409 INVALID_CASE_TRANSITION. "
                    + "근거(cites·evidence_ids)는 서버가 지금 본문에서 그대로 옮긴다")
    @PutMapping("/str-drafts/{draftNo}/revisions")
    public StrRevisionResponses.Saved revise(@PathVariable String draftNo,
                                             @Valid @RequestBody ReviseStrDraftRequest request) {
        return StrRevisionResponses.Saved.from(
                reviseStrDraft.execute(draftNo, request.caseVersion(), request.editedTexts()));
    }

    @Operation(summary = "STR 수정 이력 목록",
            description = "담당 조사역·승인자만(그 밖 403 NOT_ASSIGNEE). 열람 기록 STR_VIEW. 1번 = AI 원문")
    @GetMapping(value = "/str-drafts/{draftNo}/revisions", params = "!revision_no")
    public StrRevisionResponses.RevisionList revisions(@PathVariable String draftNo) {
        return StrRevisionResponses.RevisionList.from(listStrRevisions.list(draftNo));
    }

    @Operation(summary = "STR 수정본 한 벌 (전·후 본문)",
            description = "?revision_no= 그 수정본의 고치기 전·후 본문 전체. 없는 번호는 404 REVISION_NOT_FOUND")
    @GetMapping(value = "/str-drafts/{draftNo}/revisions", params = "revision_no")
    public StrRevisionResponses.RevisionDetail revision(@PathVariable String draftNo,
                                                        @RequestParam("revision_no") int revisionNo) {
        return StrRevisionResponses.RevisionDetail.from(listStrRevisions.one(draftNo, revisionNo));
    }

    @Operation(summary = "STR 검증 (최대 90초, body {})",
            description = "지금 저장된 본문을 AI 로 검증하고 결과를 저장한다. 응답은 AI 검증 결과 그대로"
                    + "(status PASSED·CODE_FAILED·LLM_FAILED·VERIFY_UNAVAILABLE, code_checks, llm_checks). "
                    + "담당만(403 NOT_ASSIGNEE), DRAFTED 에서만(409 INVALID_CASE_TRANSITION — 보완 요청 반려 뒤엔 저장 먼저), "
                    + "같은 초안 검증 중이면 409 VERIFY_IN_PROGRESS, AI 실패는 502")
    @PostMapping("/str-drafts/{draftNo}/verify")
    public JsonNode verify(@PathVariable String draftNo) {
        return verifyStrDraft.execute(draftNo);
    }
}
