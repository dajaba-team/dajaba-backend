package com.team.aml.cases.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.cases.application.CaseMetrics.SaveFailure;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseSummary;
import com.team.aml.cases.domain.CaseSummaryInput;
import com.team.aml.cases.domain.CaseSummaryRepository;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.CaseSummaryRequest;
import com.team.aml.shared.ai.AiDtos.CaseSummaryResponse;
import com.team.aml.shared.security.CurrentUser;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 조사 요약 갱신 (CASES 명세 API-12·§7.2, 계약서 5.3 A7, flows/02_investigation). 담당 조사역만, 편집 가능 상태에서.
 * "DB → AI → DB" 패턴(03_shared §5) — <b>이 클래스에는 트랜잭션이 없다.</b> AI 를 기다리는 동안 DB 트랜잭션·락을 잡지 않는다.
 *
 * <ol>
 *   <li>읽기 트랜잭션: 케이스 404 → version 409 CONCURRENT_UPDATE → 담당자 403 NOT_ASSIGNEE → 편집 가능 상태 409
 *       INVALID_CASE_TRANSITION(details.action=REFRESH_SUMMARY). 그다음 요약 요청을 만들고 입력 스냅샷·해시를 계산한다.</li>
 *   <li>트랜잭션 밖: {@code AiClient.caseSummary} (재시도 포함 90초). 실패는 AiClientException 그대로(502 등) — 기존 요약 유지.
 *       응답 확인: case_no 가 보낸 값과 같고 produced_at·prompt_version·summary_lines(≤6)·code_checks 가 있어야 한다
 *       (빈 code_checks 포함 — 아니면 502 AI_BAD_RESPONSE). code_checks 중 하나라도 match 가 아니면 저장하지 않고 502 SUMMARY_CODE_CHECK_FAILED.</li>
 *   <li>저장 트랜잭션: 케이스를 잠그고(FOR UPDATE) 1번 검사를 다시 한다 — 그사이 종결·재배정·다른 변경이 있었으면 409.
 *       요약 입력도 다시 만들어 해시가 다르면 409 CONCURRENT_UPDATE. 통과하면 요약 한 행 + cases.version +1(이력 행 없음).</li>
 * </ol>
 *
 * <p><b>지표</b> (SCRUM-276) — 저장하지 못하고 끝난 건수를 {@link CaseMetrics#SUMMARY_FAILURES}로 센다:
 * 2번 AI 호출 실패(AI_CALL, AI 코드)·응답 확인 실패(RESPONSE_CHECK), 3번 다시 검사 실패(SAVE, {@link SaveFailure}).
 * 1번 검사 실패는 AI 를 부르기 전이라 세지 않는다(공통 에러 지표에 있음). 원래 예외는 그대로 던진다.
 *
 * <p><b>요청 모양</b> — 규약 v0.4 §6.1(11/6). 만드는 곳은 {@link CaseSummaryInputs} 하나 — 요약 조회(API-13)의
 * is_current 판정과 같은 코드여야 해시가 맞는다.
 */
@Service
public class RefreshCaseSummary {

    /** 상태가 맞지 않을 때 details.action. 이력이 남지 않는 동작이라 CaseHistoryAction 이 아니다. */
    static final String ACTION = "REFRESH_SUMMARY";
    /** summary_lines 최대 개수 (규약 §6.1). */
    static final int MAX_SUMMARY_LINES = 6;

    private final CaseRepository cases;
    private final CaseSummaryRepository summaries;
    private final CaseSummaryInputs inputs;
    private final AiClient aiClient;
    private final CurrentUser currentUser;
    private final CaseMetrics metrics;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public RefreshCaseSummary(CaseRepository cases, CaseSummaryRepository summaries, CaseSummaryInputs inputs,
                              AiClient aiClient, CurrentUser currentUser, CaseMetrics metrics,
                              PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.summaries = summaries;
        this.inputs = inputs;
        this.aiClient = aiClient;
        this.currentUser = currentUser;
        this.metrics = metrics;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * @param expectedVersion 화면이 읽은 cases.version. 저장할 때도 같아야 한다
     * @return 저장한 요약(is_current=true)과 새 cases.version — 201 응답
     */
    public CaseSummaryResult execute(String caseNo, long expectedVersion) {
        long actorId = currentUser.require().userId();

        Prepared prepared = readTx.execute(s -> {
            Case c = cases.findByCaseNo(caseNo).orElseThrow(() -> new CaseNotFoundException(caseNo));
            check(c, expectedVersion, actorId);
            CaseSummaryRequest request = inputs.requestFor(c);
            return new Prepared(c.getId(), request, inputs.inputOf(request));
        });

        CaseSummaryResponse response = callAi(prepared.request());   // 트랜잭션 밖 (≤ 90초)
        Instant producedAt;
        try {
            producedAt = requireUsable(caseNo, response);
        } catch (AiClientException e) {
            metrics.summaryRejected(e.code());
            throw e;
        }

        try {
            return writeTx.execute(s -> {
                Case c = cases.findForUpdate(prepared.caseId()).orElseThrow(() -> new CaseNotFoundException(caseNo));
                checkAtSave(c, expectedVersion, actorId);
                CaseSummaryInput now = inputs.currentInput(c);
                if (!now.sha256().equals(prepared.input().sha256())) {
                    metrics.summarySaveFailed(SaveFailure.INPUT_HASH_MISMATCH);
                    throw new OptimisticLockException("요약을 만드는 사이 요약 입력(경보·점수·근거)이 바뀌었습니다: " + caseNo);
                }
                CaseSummary saved = summaries.save(CaseSummary.record(c.getId(), response.raw(), prepared.input(),
                        response.promptVersion(), producedAt, expectedVersion));
                cases.bumpVersion(c);   // 상태·이력은 그대로, version 만 +1 (바로 UPDATE — TouchCaseForRevision 과 같음)
                return new CaseSummaryResult(caseNo, CaseSummaryResult.Summary.of(saved, true), c.getVersion());
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            // 커밋 때 @Version 충돌 — 안에서 던진 jakarta OptimisticLockException 은 번역되지 않아 여기로 안 온다(두 번 안 셈)
            metrics.summarySaveFailed(SaveFailure.VERSION_MISMATCH);
            throw e;
        }
    }

    /** 2번 AI 호출. 실패는 세고 그대로 던진다 — 기존 요약은 그대로. */
    private CaseSummaryResponse callAi(CaseSummaryRequest request) {
        try {
            return aiClient.caseSummary(request);
        } catch (AiClientException e) {
            metrics.summaryAiFailed(e.code());
            throw e;
        } catch (RuntimeException e) {
            metrics.summaryAiFailed(null);   // AiClient 밖으로 샌 예외 — AI_OTHER
            throw e;
        }
    }

    /** 3번 다시 검사. 1번과 같은 검사이고, 막히면 이유별로 센다. */
    private void checkAtSave(Case c, long expectedVersion, long actorId) {
        try {
            check(c, expectedVersion, actorId);
        } catch (OptimisticLockException e) {
            metrics.summarySaveFailed(SaveFailure.VERSION_MISMATCH);
            throw e;
        } catch (NotAssigneeException e) {
            metrics.summarySaveFailed(SaveFailure.ASSIGNEE_CHANGED);
            throw e;
        } catch (InvalidCaseTransitionException e) {
            metrics.summarySaveFailed(SaveFailure.STATE_CHANGED);
            throw e;
        }
    }

    /** 1·3번 공통 검사. 순서는 CaseChanges·AttachDraftToCase 와 같다. */
    private static void check(Case c, long expectedVersion, long actorId) {
        if (c.getVersion() != expectedVersion) {
            throw new OptimisticLockException("케이스가 그 사이 바뀌었습니다: " + c.getCaseNo()
                    + " (expected " + expectedVersion + ", current " + c.getVersion() + ")");
        }
        if (c.getAssigneeId() == null || c.getAssigneeId() != actorId) {
            throw new NotAssigneeException(c.getCaseNo());
        }
        if (!c.isEditableByAssignee()) {
            throw InvalidCaseTransitionException.notEditable(c.getStatus(), c.getRejectionReasonCode(), ACTION);
        }
    }

    /** 저장해도 되는 응답인지. 아니면 기존 요약을 그대로 두고 502. @return produced_at */
    private static Instant requireUsable(String caseNo, CaseSummaryResponse r) {
        if (!caseNo.equals(r.caseNo())) {
            throw badResponse("AI 요약 응답 case_no 가 요청과 다름");
        }
        if (r.promptVersion() == null) {
            throw badResponse("AI 요약 응답에 generation_meta.prompt_version 없음");
        }
        if (r.summaryLines() == null || r.summaryLines().size() > MAX_SUMMARY_LINES) {
            throw badResponse("AI 요약 응답 summary_lines 가 없거나 " + MAX_SUMMARY_LINES + "개를 넘음");
        }
        Instant producedAt;
        try {
            producedAt = r.producedAt() == null ? null : Instant.parse(r.producedAt());
        } catch (DateTimeParseException e) {
            producedAt = null;
        }
        if (producedAt == null) {
            throw badResponse("AI 요약 응답 produced_at 이 없거나 UTC 시각이 아님");
        }
        JsonNode checks = r.codeChecks();
        if (checks == null || !checks.isArray() || checks.isEmpty()) {
            // AI 요약은 코드 검사를 항상 돌려준다(AMOUNT·DATE·COUNT·ACCOUNT_ID). 비어 있으면 "전부 match" 로 보지 않는다 (#65 리뷰)
            throw badResponse("AI 요약 응답에 code_checks 가 없거나 비어 있음");
        }
        List<String> failed = new ArrayList<>();
        checks.forEach(ch -> {
            if (!ch.path("match").asBoolean(false)) {
                failed.add(ch.path("check").asText("UNKNOWN"));
            }
        });
        if (!failed.isEmpty()) {
            // 규약 §6.1 — 코드 검사가 실패해도 AI 는 200. 백엔드는 전부 match 일 때만 저장한다.
            throw new AiClientException("SUMMARY_CODE_CHECK_FAILED", "AI 요약의 코드 검사가 통과하지 못했습니다",
                    Map.of("case_no", caseNo, "failed_checks", List.copyOf(failed)), 502);
        }
        return producedAt;
    }

    private static AiClientException badResponse(String message) {
        return new AiClientException("AI_BAD_RESPONSE", message, Map.of(), 502);
    }

    private record Prepared(long caseId, CaseSummaryRequest request, CaseSummaryInput input) {}
}
