package com.team.aml.cases.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.team.aml.cases.application.CaseMetrics.SaveRejection;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CaseSummaryInput;
import com.team.aml.cases.domain.CaseSummaryRepository;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.CaseSummaryResponse;
import com.team.aml.shared.ai.AiDtos.StrDraftResponse.GenerationMetaView;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB 없이 — RefreshCaseSummary 가 AI 를 부른 뒤의 결과를 aml.cases.summary.refreshes 로 나눠 센다 (SCRUM-276).
 * 저장까지 끝나는 경우와 실제 DB 의 version·해시 충돌은 RefreshCaseSummaryDbTest 가 본다.
 */
class RefreshCaseSummaryMetricsTest {

    private static final String CASE_NO = "CASE-2022-000057";
    private static final long CASE_ID = 57L;
    private static final long VERSION = 3L;
    private static final long ACTOR = 7L;

    private final ObjectMapper om = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaseRepository cases = mock(CaseRepository.class);
    private final CaseSummaryInputs inputs = mock(CaseSummaryInputs.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final RefreshCaseSummary refresh = new RefreshCaseSummary(cases, mock(CaseSummaryRepository.class), inputs,
            aiClient, currentUser, mock(PlatformTransactionManager.class), new CaseMetrics(registry));

    RefreshCaseSummaryMetricsTest() {
        when(currentUser.require()).thenReturn(new AppUserDetails(ACTOR, "inv", "조사역", "INVESTIGATOR"));
        Case c = editableCase(VERSION, ACTOR);
        when(cases.findByCaseNo(CASE_NO)).thenReturn(Optional.of(c));
        when(cases.findForUpdate(CASE_ID)).thenReturn(Optional.of(c));
        when(inputs.inputOf(any())).thenReturn(input("h1"));
        when(inputs.currentInput(any())).thenReturn(input("h1"));
    }

    private double refreshes(String result, String code) {
        Counter c = registry.find(CaseMetrics.SUMMARY_REFRESHES).tag("result", result).tag("code", code).counter();
        return c == null ? 0 : c.count();
    }

    private double total() {
        return registry.find(CaseMetrics.SUMMARY_REFRESHES).counters().stream().mapToDouble(Counter::count).sum();
    }

    // --- AI 단계 --------------------------------------------------------------

    @Test
    void 실패_AI_오류는_AI_FAILED와_AI_코드로_세고_예외는_그대로() {
        when(aiClient.caseSummary(any())).thenThrow(new AiClientException("LLM_UNAVAILABLE", "down"));

        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("LLM_UNAVAILABLE"));
        assertThat(refreshes("AI_FAILED", "LLM_UNAVAILABLE")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void 실패_응답_확인_실패도_AI_FAILED_코드검사는_SUMMARY_CODE_CHECK_FAILED() {
        when(aiClient.caseSummary(any())).thenReturn(response(false));

        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION))
                .isInstanceOfSatisfying(AiClientException.class,
                        e -> assertThat(e.code()).isEqualTo("SUMMARY_CODE_CHECK_FAILED"));
        assertThat(refreshes("AI_FAILED", "SUMMARY_CODE_CHECK_FAILED")).isEqualTo(1);
    }

    @Test
    void 경계_AI를_부르기_전_검사_실패는_세지_않는다() {
        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION - 1))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(total()).isZero();
    }

    // --- 저장 단계 -------------------------------------------------------------

    @Test
    void 실패_그사이_version이_바뀌면_SAVE_REJECTED_VERSION_CHANGED() {
        when(aiClient.caseSummary(any())).thenReturn(response(true));
        Case changed = editableCase(VERSION + 1, ACTOR);
        when(cases.findForUpdate(CASE_ID)).thenReturn(Optional.of(changed));

        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION)).isInstanceOf(OptimisticLockException.class);
        assertThat(refreshes("SAVE_REJECTED", "VERSION_CHANGED")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    void 실패_그사이_요약_입력이_바뀌면_SAVE_REJECTED_INPUT_CHANGED_응답은_같은_OptimisticLock() {
        when(aiClient.caseSummary(any())).thenReturn(response(true));
        when(inputs.currentInput(any())).thenReturn(input("h2"));

        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION))
                .isInstanceOf(OptimisticLockException.class);   // GlobalExceptionHandler → 409 CONCURRENT_UPDATE 그대로
        assertThat(refreshes("SAVE_REJECTED", "INPUT_CHANGED")).isEqualTo(1);
        assertThat(refreshes("SAVE_REJECTED", "VERSION_CHANGED")).isZero();
    }

    @Test
    void 실패_그사이_재배정되면_SAVE_REJECTED_NOT_ASSIGNEE() {
        when(aiClient.caseSummary(any())).thenReturn(response(true));
        Case reassigned = editableCase(VERSION, ACTOR + 1);
        when(cases.findForUpdate(CASE_ID)).thenReturn(Optional.of(reassigned));

        assertThatThrownBy(() -> refresh.execute(CASE_NO, VERSION)).isInstanceOf(NotAssigneeException.class);
        assertThat(refreshes("SAVE_REJECTED", "NOT_ASSIGNEE")).isEqualTo(1);
    }

    @Test
    void 정상_저장_단계_예외를_고정_code로_바꾼다() {
        assertThat(RefreshCaseSummary.rejectionOf(new RefreshCaseSummary.SummaryInputChangedException(CASE_NO)))
                .isEqualTo(SaveRejection.INPUT_CHANGED);
        assertThat(RefreshCaseSummary.rejectionOf(new OptimisticLockException("v")))
                .isEqualTo(SaveRejection.VERSION_CHANGED);
        assertThat(RefreshCaseSummary.rejectionOf(new ObjectOptimisticLockingFailureException(Case.class, CASE_ID)))
                .isEqualTo(SaveRejection.VERSION_CHANGED);
        assertThat(RefreshCaseSummary.rejectionOf(new NotAssigneeException(CASE_NO)))
                .isEqualTo(SaveRejection.NOT_ASSIGNEE);
        assertThat(RefreshCaseSummary.rejectionOf(
                InvalidCaseTransitionException.notEditable(CaseStatus.CLOSED, null, RefreshCaseSummary.ACTION)))
                .isEqualTo(SaveRejection.NOT_EDITABLE);
        assertThat(RefreshCaseSummary.rejectionOf(new CaseNotFoundException(CASE_NO)))
                .isEqualTo(SaveRejection.CASE_NOT_FOUND);
        assertThat(RefreshCaseSummary.rejectionOf(new IllegalStateException("db"))).isEqualTo(SaveRejection.OTHER);
    }

    // --- 테스트 전용 ---

    private static Case editableCase(long version, long assigneeId) {
        Case c = mock(Case.class);
        when(c.getId()).thenReturn(CASE_ID);
        when(c.getCaseNo()).thenReturn(CASE_NO);
        when(c.getVersion()).thenReturn(version);
        when(c.getAssigneeId()).thenReturn(assigneeId);
        when(c.isEditableByAssignee()).thenReturn(true);
        return c;
    }

    private CaseSummaryInput input(String hash) {
        return new CaseSummaryInput(om.createObjectNode(), hash);
    }

    private CaseSummaryResponse response(boolean checksMatch) {
        ArrayNode checks = om.createArrayNode();
        checks.addObject().put("check", "AMOUNT_MATCH").put("match", checksMatch);
        return new CaseSummaryResponse("2026-11-20T01:00:00Z", CASE_NO, "gen-1", List.of("요약 한 줄"), checks,
                new GenerationMetaView("case_summary_v1"), om.createObjectNode());
    }
}
