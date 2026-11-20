package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseQuestion;
import com.team.aml.cases.domain.CaseQuestionRepository;
import com.team.aml.cases.domain.CaseQuestionStatus;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.QuestionOutOfScopeReason;
import com.team.aml.cases.domain.QuestionRateLimit;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos.AskRequest;
import com.team.aml.shared.ai.AiDtos.AskResponse;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Instant;

/**
 * A8 질문하기 (계약서 5.3, 설계 문서 7.4, SCRUM-302). 담당 조사역·승인자, 케이스 상태 무관.
 * "DB → AI → DB" 패턴(03_shared §5) — <b>이 클래스에는 트랜잭션이 없다.</b> AI 를 기다리는 30초 동안 DB 연결을 잡지 않는다.
 *
 * <ol>
 *   <li>짧은 읽기: 케이스 404 → 권한 403(NOT_ASSIGNEE·FORBIDDEN) → 질문 400(details.question) → 최근 60초 본인 질문 수가
 *       한도(aml.ai.ask-per-minute) 이상이면 429 QUESTION_RATE_LIMITED → 요약 갱신과 <b>같은 입력 조립</b>
 *       ({@link CaseSummaryInputs#requestFor}) + question. 여기서 걸리면 AI 를 부르지 않는다.</li>
 *   <li>트랜잭션 밖: {@code AiClient.ask} (재시도 포함 30초). 응답 칸·상태별 규칙·근거 거래 범위 확인도 거기서 — 틀리면 502
 *       AI_BAD_RESPONSE. 실패는 AiClientException 그대로(502) — <b>행을 남기지 않는다</b>(호출 흔적은 ai_call_logs).</li>
 *   <li>새 짧은 트랜잭션: case_questions 한 행. 네 상태 모두 저장하고 201.</li>
 * </ol>
 * 켜기 설정(aml.ai.ask-enabled)은 컨트롤러가 먼저 본다 — 꺼져 있으면 여기까지 오지 않는다.
 */
@Service
public class AskCaseQuestion {

    private static final Logger log = LoggerFactory.getLogger(AskCaseQuestion.class);

    private final CaseRepository cases;
    private final CaseQuestionRepository questions;
    private final CaseSummaryInputs inputs;
    private final AiClient aiClient;
    private final CurrentUser currentUser;
    private final UserRefs userRefs;
    private final AmlProperties properties;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public AskCaseQuestion(CaseRepository cases, CaseQuestionRepository questions, CaseSummaryInputs inputs,
                           AiClient aiClient, CurrentUser currentUser, UserRefs userRefs, AmlProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.questions = questions;
        this.inputs = inputs;
        this.aiClient = aiClient;
        this.currentUser = currentUser;
        this.userRefs = userRefs;
        this.properties = properties;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /** @param question 받은 그대로(앞뒤 공백 포함) — 자르지 않고 AI 에 보내고 그대로 저장한다 */
    public CaseQuestionResult execute(String caseNo, String question) {
        AppUserDetails me = currentUser.require();

        Prepared prepared = readTx.execute(s -> {
            Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
            CaseQuestionAccess.require(c, me);
            CaseQuestion.requireValidQuestion(question);
            checkRateLimit(caseNo, me.userId());
            return new Prepared(c.getId(), AskRequest.of(inputs.requestFor(c), question));
        });

        AskResponse response = aiClient.ask(prepared.request());   // 트랜잭션 밖 (≤ 30초)

        CaseQuestion saved = writeTx.execute(s -> questions.save(CaseQuestion.record(prepared.caseId(), question,
                CaseQuestionStatus.valueOf(response.status().name()),
                response.outOfScopeReason() == null ? null
                        : QuestionOutOfScopeReason.valueOf(response.outOfScopeReason().name()),
                response.answer(), response.evidenceIds(), response.generationId(), MDC.get("request_id"))));
        // 본문은 찍지 않는다(NFR-COM-04) — 번호·상태·길이만
        log.info("case question saved case_no={} question_no={} status={} question_length={}", caseNo,
                saved.getId(), saved.getStatus(), Normalizer.normalize(question, Normalizer.Form.NFC).length());
        return CaseQuestionResult.of(saved, caseNo, userRefs.of(me.userId()));
    }

    /** 최근 60초 이 사람의 질문 수 — 케이스와 상관없이 센다(설계 문서 7.4 "사용자당 분당 5회"). */
    private void checkRateLimit(String caseNo, long userId) {
        Instant now = Instant.now();
        Instant since = now.minus(QuestionRateLimit.WINDOW);
        int limit = properties.ai().askPerMinute();
        long recent = questions.countByCreatedByAndCreatedAtAfter(userId, since);
        Instant oldest = recent < limit ? null
                : questions.findFirstByCreatedByAndCreatedAtAfterOrderByCreatedAtAsc(userId, since)
                        .map(CaseQuestion::getCreatedAt).orElse(null);
        QuestionRateLimit.check(caseNo, limit, recent, oldest, now);
    }

    private record Prepared(long caseId, AskRequest request) {}
}
