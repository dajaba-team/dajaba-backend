package com.team.aml.cases.api;

import com.team.aml.cases.application.AskCaseQuestion;
import com.team.aml.cases.application.CaseQuestionResult;
import com.team.aml.cases.application.ListCaseQuestions;
import com.team.aml.shared.api.PageResponse;
import com.team.aml.shared.config.AmlProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 질문 답변 — A8 질문하기 · C9 지난 질문 (계약서 5.2·5.3, 설계 문서 7절, SCRUM-302).
 * 관리자는 SecurityConfig URL 규칙이 403 FORBIDDEN. 담당 여부·질문·횟수 검사는 유스케이스.
 * <b>aml.ai.ask-enabled=false(기본)면 두 API 모두 404 NOT_FOUND</b> — 없는 주소와 같은 응답(설계 문서 11.3 "끄면 흔적 없이").
 */
@Tag(name = "cases")
@RestController
@RequestMapping("/api/v1/cases/{caseNo}/questions")
public class CaseQuestionController {

    private final AskCaseQuestion askCaseQuestion;
    private final ListCaseQuestions listCaseQuestions;
    private final AmlProperties properties;

    public CaseQuestionController(AskCaseQuestion askCaseQuestion, ListCaseQuestions listCaseQuestions,
                                  AmlProperties properties) {
        this.askCaseQuestion = askCaseQuestion;
        this.listCaseQuestions = listCaseQuestions;
        this.properties = properties;
    }

    @Operation(summary = "질문하기",
            description = "담당 조사역·승인자, 상태 무관, 최대 30초. question 1~300자(NFC 뒤 UTF-16, 앞뒤 공백 그대로), "
                    + "비었거나 공백만이면 400 details.question. 담당 아닌 조사역 403 NOT_ASSIGNEE, 관리자 403 FORBIDDEN, "
                    + "최근 60초에 한도만큼 물었으면 429 QUESTION_RATE_LIMITED(details.retry_after_seconds). "
                    + "네 상태(ANSWERED·NO_DATA·UNVERIFIED·OUT_OF_SCOPE) 모두 201. AI 실패 502 는 기록을 남기지 않는다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseQuestionResult ask(@PathVariable String caseNo, @RequestBody AskQuestionRequest request)
            throws NoResourceFoundException {
        requireEnabled(HttpMethod.POST, caseNo);
        return askCaseQuestion.execute(caseNo, request.question());
    }

    @Operation(summary = "지난 질문",
            description = "담당 조사역·승인자. 최신 먼저, size ≤ 100(기본 20). 한 줄 모양은 질문하기 응답과 같다")
    @GetMapping
    public PageResponse<CaseQuestionResult> list(@PathVariable String caseNo,
                                                 @Valid @ParameterObject @ModelAttribute CaseQuestionQuery query)
            throws NoResourceFoundException {
        requireEnabled(HttpMethod.GET, caseNo);
        return PageResponse.from(listCaseQuestions.execute(caseNo, query.pageOrDefault(), query.sizeOrDefault()));
    }

    private void requireEnabled(HttpMethod method, String caseNo) throws NoResourceFoundException {
        if (!properties.ai().askEnabled()) {
            throw new NoResourceFoundException(method, "api/v1/cases/" + caseNo + "/questions");
        }
    }
}
