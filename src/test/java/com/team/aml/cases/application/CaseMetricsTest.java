package com.team.aml.cases.application;

import com.team.aml.cases.application.CaseMetrics.SaveFailure;
import com.team.aml.cases.domain.CaseAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CASES 지표 (SCRUM-276). 이름은 aml.cases.*, 태그 값은 정해진 것만, 세다가 실패해도 던지지 않는다.
 */
class CaseMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaseMetrics metrics = new CaseMetrics(registry);

    private double failures(String stage, String reason) {
        Counter c = registry.find(CaseMetrics.SUMMARY_FAILURES).tag("stage", stage).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_충돌은_동작별로_센다_태그는_action_하나() {
        metrics.conflict(CaseAction.ESCALATE);
        metrics.conflict(CaseAction.ESCALATE);
        metrics.conflict(CaseAction.ASSIGN);

        assertThat(registry.get(CaseMetrics.CONFLICTS).tag("action", "ESCALATE").counter().count()).isEqualTo(2);
        assertThat(registry.get(CaseMetrics.CONFLICTS).tag("action", "ASSIGN").counter().count()).isEqualTo(1);
        assertThat(registry.get(CaseMetrics.CONFLICTS).tag("action", "ASSIGN").counter().getId().getTags())
                .extracting(t -> t.getKey()).containsExactly("action");
    }

    @Test
    void 정상_요약_실패는_단계와_이유로_나눠_센다() {
        metrics.summaryAiFailed("AI_TIMEOUT");
        metrics.summaryRejected("SUMMARY_CODE_CHECK_FAILED");
        metrics.summaryRejected("AI_BAD_RESPONSE");
        metrics.summarySaveFailed(SaveFailure.VERSION_MISMATCH);
        metrics.summarySaveFailed(SaveFailure.INPUT_HASH_MISMATCH);

        assertThat(failures("AI_CALL", "AI_TIMEOUT")).isEqualTo(1);
        assertThat(failures("RESPONSE_CHECK", "SUMMARY_CODE_CHECK_FAILED")).isEqualTo(1);
        assertThat(failures("RESPONSE_CHECK", "AI_BAD_RESPONSE")).isEqualTo(1);
        assertThat(failures("SAVE", "VERSION_MISMATCH")).isEqualTo(1);
        assertThat(failures("SAVE", "INPUT_HASH_MISMATCH")).isEqualTo(1);
        assertThat(registry.get(CaseMetrics.SUMMARY_FAILURES).tag("stage", "SAVE").counters())
                .allSatisfy(c -> assertThat(c.getId().getTags()).extracting(t -> t.getKey())
                        .containsExactlyInAnyOrder("stage", "reason"));
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_표에_없는_AI_코드와_null은_AI_OTHER() {
        metrics.summaryAiFailed("BRAND_NEW_AI_CODE");
        metrics.summaryAiFailed("CASE-2022-000057 계좌 10_8000EBD30");   // 형식 밖 값이 태그로 새지 않는다
        metrics.summaryAiFailed(null);

        assertThat(failures("AI_CALL", "AI_OTHER")).isEqualTo(3);
        assertThat(registry.find(CaseMetrics.SUMMARY_FAILURES).counters()).hasSize(1);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_레지스트리가_예외를_던져도_밖으로_안_던진다() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("registry down"));
        CaseMetrics failing = new CaseMetrics(broken);

        assertThatCode(() -> {
            failing.conflict(CaseAction.CLOSE);
            failing.summaryAiFailed("AI_TIMEOUT");
            failing.summarySaveFailed(SaveFailure.STATE_CHANGED);
        }).doesNotThrowAnyException();
    }
}
