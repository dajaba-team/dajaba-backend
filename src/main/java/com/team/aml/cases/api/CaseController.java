package com.team.aml.cases.api;

import com.team.aml.cases.application.AssignCase;
import com.team.aml.cases.application.CaseTransactions;
import com.team.aml.cases.application.CaseSummaryResult;
import com.team.aml.cases.application.CloseCase;
import com.team.aml.cases.application.EscalateCase;
import com.team.aml.cases.application.GetCaseDetail;
import com.team.aml.cases.application.GetCaseSummary;
import com.team.aml.cases.application.ListCaseHistories;
import com.team.aml.cases.application.ListCaseNoteHistories;
import com.team.aml.cases.application.ListCases;
import com.team.aml.cases.application.ReassignCase;
import com.team.aml.cases.application.RefreshCaseSummary;
import com.team.aml.cases.application.UpdateCaseNotes;
import com.team.aml.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 케이스 목록·상세·근거 거래·그래프·이력·배정·담당자 변경·메모·에스컬레이션·종료·AI 조사 요약
 * (CASES 명세 API-01~13·15, 계약서 C1~C8·A1·A2·A3·A4·A5·A7).
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
    private final RefreshCaseSummary refreshCaseSummary;
    private final GetCaseSummary getCaseSummary;
    private final UpdateCaseNotes updateCaseNotes;
    private final ListCaseNoteHistories listCaseNoteHistories;
    private final ReassignCase reassignCase;
    private final CaseTransactions caseTransactions;
    private final ListCaseHistories listCaseHistories;

    public CaseController(ListCases listCases, GetCaseDetail getCaseDetail, AssignCase assignCase,
                          EscalateCase escalateCase, CloseCase closeCase, RefreshCaseSummary refreshCaseSummary,
                          GetCaseSummary getCaseSummary, UpdateCaseNotes updateCaseNotes,
                          ListCaseNoteHistories listCaseNoteHistories, ReassignCase reassignCase,
                          CaseTransactions caseTransactions, ListCaseHistories listCaseHistories) {
        this.listCases = listCases;
        this.getCaseDetail = getCaseDetail;
        this.assignCase = assignCase;
        this.escalateCase = escalateCase;
        this.closeCase = closeCase;
        this.refreshCaseSummary = refreshCaseSummary;
        this.getCaseSummary = getCaseSummary;
        this.updateCaseNotes = updateCaseNotes;
        this.listCaseNoteHistories = listCaseNoteHistories;
        this.reassignCase = reassignCase;
        this.caseTransactions = caseTransactions;
        this.listCaseHistories = listCaseHistories;
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

    @Operation(summary = "근거 거래",
            description = "scope=EVIDENCE(기본, 경보에 묶인 거래 전체)|CONTEXT(경보 밖 참고 거래 — 아직 저장소가 없어 빈 목록). "
                    + "direction 은 케이스 계좌 기준 INBOUND·OUTBOUND·INDIRECT(경유). sort=ts|transaction_id,asc|desc "
                    + "(기본 ts,asc), size ≤ 100. 계좌는 가린 값과 account_ref 만")
    @GetMapping("/{caseNo}/transactions")
    public PageResponse<CaseTransactionResponse> transactions(
            @PathVariable String caseNo, @Valid @ParameterObject @ModelAttribute CaseTransactionQuery query) {
        return PageResponse.from(caseTransactions.list(caseNo, query.scopeOrDefault(), query.order(),
                query.pageOrDefault(), query.sizeOrDefault()).map(CaseTransactionResponse::from));
    }

    @Operation(summary = "근거 거래 원본",
            description = "이 케이스의 근거 거래가 아니면(없는 거래 포함) 404 EVIDENCE_NOT_FOUND. 열람 기록을 남긴다")
    @GetMapping("/{caseNo}/evidence/{transactionId}")
    public EvidenceResponse evidence(@PathVariable String caseNo, @PathVariable long transactionId) {
        return EvidenceResponse.from(caseTransactions.evidence(caseNo, transactionId));
    }

    @Operation(summary = "자금 흐름 그래프",
            description = "limit 기본 200(1~1,000), 시각 순. 더 있으면 truncated=true, total_transaction_count 는 전체")
    @GetMapping("/{caseNo}/graph")
    public CaseGraphResponse graph(@PathVariable String caseNo,
                                   @Valid @ParameterObject @ModelAttribute CaseGraphQuery query) {
        return CaseGraphResponse.from(caseTransactions.graph(caseNo, query.limitOrDefault()));
    }

    @Operation(summary = "상태·업무 이력 전체",
            description = "최신 먼저, NOTE 제외(메모 전문은 note-histories). action 하나로 거를 수 있다(NOTE 는 400). size ≤ 100")
    @GetMapping("/{caseNo}/histories")
    public PageResponse<GetCaseDetail.History> histories(
            @PathVariable String caseNo, @Valid @ParameterObject @ModelAttribute CaseHistoryQuery query) {
        return PageResponse.from(listCaseHistories.execute(caseNo, query.action(), query.pageOrDefault(),
                query.sizeOrDefault()));
    }

    @Operation(summary = "담당 맡기 / 배정",
            description = "OPEN → REVIEWING. 조사역은 본인만(남 지정 409 INVALID_ASSIGNEE·SELF_ONLY), ADMIN 은 assignee 필수. "
                    + "버전 다르면 409 CONCURRENT_UPDATE")
    @PostMapping("/{caseNo}/assign")
    public CaseChangeResponse assign(@PathVariable String caseNo, @Valid @RequestBody AssignCaseRequest request) {
        return CaseChangeResponse.from(assignCase.execute(caseNo, request.version(), request.assignee()));
    }

    @Operation(summary = "담당자 변경",
            description = "ADMIN 만(그 밖은 403 FORBIDDEN). REVIEWING·ESCALATED·DRAFTED·REJECTED 에서 상태 그대로 담당만 바꾼다. "
                    + "없는 login_id 404 USER_NOT_FOUND, 비활성·조사역 아님 409 INVALID_ASSIGNEE, 버전 다르면 409. "
                    + "같은 담당자면 그대로(version 안 오름). 이력 REASSIGN 에 넘긴·받은 사람")
    @PostMapping("/{caseNo}/reassign")
    public CaseChangeResponse reassign(@PathVariable String caseNo, @Valid @RequestBody ReassignCaseRequest request) {
        return CaseChangeResponse.from(
                reassignCase.execute(caseNo, request.version(), request.assignee(), request.comment()));
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

    @Operation(summary = "AI 조사 요약 생성·갱신",
            description = "담당만(403 NOT_ASSIGNEE), 편집 가능 상태만(409 INVALID_CASE_TRANSITION·action=REFRESH_SUMMARY). "
                    + "버전 다르면 409 CONCURRENT_UPDATE. AI 실패·코드 검사 실패(502 SUMMARY_CODE_CHECK_FAILED)는 "
                    + "기존 요약을 그대로 둔다. 성공하면 201, case_version 은 +1 (이력 행 없음)")
    @PostMapping("/{caseNo}/refresh-summary")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseSummaryResult refreshSummary(@PathVariable String caseNo,
                                            @Valid @RequestBody RefreshSummaryRequest request) {
        return refreshCaseSummary.execute(caseNo, request.version());
    }

    @Operation(summary = "현재 AI 조사 요약",
            description = "조회 3역할. 요약이 없으면 summary: null. is_current 는 지금 요약 입력(경보·점수·근거)의 해시로 판정")
    @GetMapping("/{caseNo}/summary")
    public GetCaseSummary.Result summary(@PathVariable String caseNo) {
        return getCaseSummary.execute(caseNo);
    }

    @Operation(summary = "조사 메모 저장",
            description = "담당만(403 NOT_ASSIGNEE), 편집 가능 상태만(409 INVALID_CASE_TRANSITION·action=UPDATE_NOTES). "
                    + "0~10,000자, 빈 문자열은 비우기, null·초과는 400. 버전 다르면 내용이 같아도 409 CONCURRENT_UPDATE. "
                    + "바뀌면 NOTE 이력 + version +1, 같은 내용이면 그대로(version 안 오름)")
    @PutMapping("/{caseNo}/notes")
    public CaseChangeResponse updateNotes(@PathVariable String caseNo, @Valid @RequestBody UpdateNotesRequest request) {
        return CaseChangeResponse.from(
                updateCaseNotes.execute(caseNo, request.version(), request.investigatorNotes()));
    }

    @Operation(summary = "메모 이력",
            description = "조회 3역할. 최신 먼저, before_text 는 바로 앞 메모(첫 기록은 null), 비운 기록은 after_text \"\". "
                    + "size ≤ 100")
    @GetMapping("/{caseNo}/note-histories")
    public PageResponse<ListCaseNoteHistories.NoteHistory> noteHistories(
            @PathVariable String caseNo, @Valid @ParameterObject @ModelAttribute NoteHistoryQuery query) {
        return PageResponse.from(
                listCaseNoteHistories.execute(caseNo, query.pageOrDefault(), query.sizeOrDefault()));
    }
}
