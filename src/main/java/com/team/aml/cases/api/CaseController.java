package com.team.aml.cases.api;

import com.team.aml.cases.application.AssignCase;
import com.team.aml.cases.application.CloseCase;
import com.team.aml.cases.application.EscalateCase;
import com.team.aml.cases.application.GetCaseDetail;
import com.team.aml.cases.application.ListCases;
import com.team.aml.shared.api.PageResponse;
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
 * 케이스 목록·상세·배정·에스컬레이션·종료 (CASES 명세 API-01·02·03·06·07, 계약서 C1·C2·A1·A4·A5).
 * 조회 3역할 모두 — SecurityConfig 의 /api/** authenticated. 담당자·상태 검사는 유스케이스·엔티티가 한다.
 * 응답은 유스케이스 record 를 화면 모양으로 바꾼 것 — 원본 account_key 는 내보내지 않는다(AccountDisplay).
 */
@Tag(name = "cases")
@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {

    private final ListCases listCases;
    private final GetCaseDetail getCaseDetail;
    private final AssignCase assignCase;
    private final EscalateCase escalateCase;
    private final CloseCase closeCase;

    public CaseController(ListCases listCases, GetCaseDetail getCaseDetail, AssignCase assignCase,
                          EscalateCase escalateCase, CloseCase closeCase) {
        this.listCases = listCases;
        this.getCaseDetail = getCaseDetail;
        this.assignCase = assignCase;
        this.escalateCase = escalateCase;
        this.closeCase = closeCase;
    }

    @Operation(summary = "케이스 목록",
            description = "status 쉼표 여럿, assignee=me|login_id, from·to 케이스 생성일(한국 날짜, 양 끝 포함), "
                    + "sort=score|created_at|status,asc|desc (기본 score,desc — 점수가 생기기 전엔 created_at DESC), size ≤ 100")
    @GetMapping
    public PageResponse<CaseListItemResponse> list(@Valid @ParameterObject @ModelAttribute CaseListQuery query) {
        return PageResponse.from(listCases.execute(query.toQuery()).map(CaseListItemResponse::from));
    }

    @Operation(summary = "케이스 상세", description = "열람 기록을 남긴다. 없으면 404 CASE_NOT_FOUND. 버튼은 available_actions")
    @GetMapping("/{caseNo}")
    public CaseDetailResponse detail(@PathVariable String caseNo) {
        return CaseDetailResponse.from(getCaseDetail.execute(caseNo));
    }

    @Operation(summary = "담당 맡기 / 배정",
            description = "OPEN → REVIEWING. 조사역은 본인만(남 지정 409 INVALID_ASSIGNEE·SELF_ONLY), ADMIN 은 assignee 필수. "
                    + "버전 다르면 409 CONCURRENT_UPDATE")
    @PostMapping("/{caseNo}/assign")
    public CaseChangeResponse assign(@PathVariable String caseNo, @Valid @RequestBody AssignCaseRequest request) {
        return CaseChangeResponse.from(assignCase.execute(caseNo, request.version(), request.assignee()));
    }

    @Operation(summary = "에스컬레이션",
            description = "REVIEWING → ESCALATED, 담당만(403 NOT_ASSIGNEE). AI·초안 생성은 부르지 않는다")
    @PostMapping("/{caseNo}/escalate")
    public CaseChangeResponse escalate(@PathVariable String caseNo, @Valid @RequestBody EscalateCaseRequest request) {
        return CaseChangeResponse.from(escalateCase.execute(caseNo, request.version(), request.comment()));
    }

    @Operation(summary = "종료 (초안 없음)",
            description = "REVIEWING·ESCALATED → CLOSED, 담당만. 초안이 있으면 409 CLOSE_REQUIRES_STR — close-with-draft 로")
    @PostMapping("/{caseNo}/close")
    public CaseChangeResponse close(@PathVariable String caseNo, @Valid @RequestBody CloseCaseRequest request) {
        return CaseChangeResponse.from(
                closeCase.execute(caseNo, request.version(), request.closeReason(), request.comment()));
    }
}
