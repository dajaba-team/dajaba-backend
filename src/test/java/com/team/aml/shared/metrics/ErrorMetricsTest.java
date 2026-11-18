package com.team.aml.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 에러 코드별 건수 aml.http.errors (03_shared §13, SCRUM-271).
 * 태그는 code·status 둘만, code 값은 정해진 것만 — 형식 밖은 OTHER, 502 AI 코드가 §2 표에 없으면 AI_OTHER.
 * 세다가 예외가 나도 밖으로 던지지 않는다(fail-open) — 에러 응답이 그대로 나가야 한다.
 */
class ErrorMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ErrorMetrics metrics = new ErrorMetrics(registry);

    private double count(String code, String status) {
        Counter c = registry.find(ErrorMetrics.NAME).tag("code", code).tag("status", status).counter();
        return c == null ? 0 : c.count();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_코드와_상태별로_1씩_센다() {
        metrics.record("INVALID_CASE_TRANSITION", 409);
        metrics.record("INVALID_CASE_TRANSITION", 409);
        metrics.record("CASE_NOT_FOUND", 404);

        assertThat(count("INVALID_CASE_TRANSITION", "409")).isEqualTo(2);
        assertThat(count("CASE_NOT_FOUND", "404")).isEqualTo(1);
    }

    @Test
    void 정상_태그는_code와_status_둘뿐이다() {
        metrics.record("UNAUTHENTICATED", 401);

        Meter.Id id = registry.get(ErrorMetrics.NAME).counter().getId();
        assertThat(id.getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("code", "status");
    }

    @Test
    void 정상_정해진_AI_코드는_502에서도_그대로() {
        metrics.record("LLM_UNAVAILABLE", 502);
        metrics.record("AI_TIMEOUT", 502);

        assertThat(count("LLM_UNAVAILABLE", "502")).isEqualTo(1);
        assertThat(count("AI_TIMEOUT", "502")).isEqualTo(1);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_표에_없는_502_AI_코드는_AI_OTHER() {
        metrics.record("BRAND_NEW_AI_CODE", 502);

        assertThat(count("AI_OTHER", "502")).isEqualTo(1);
        assertThat(count("BRAND_NEW_AI_CODE", "502")).isZero();
    }

    @Test
    void 경계_형식_밖_코드는_OTHER() {
        metrics.record("CASE-2022-000057", 404);   // 화면 번호가 code 로 들어와도 태그에 안 남는다
        metrics.record("lower_case", 400);
        metrics.record(null, 500);
        metrics.record("A".repeat(65), 409);

        assertThat(count("OTHER", "404")).isEqualTo(1);
        assertThat(count("OTHER", "400")).isEqualTo(1);
        assertThat(count("OTHER", "500")).isEqualTo(1);
        assertThat(count("OTHER", "409")).isEqualTo(1);
        assertThat(registry.find(ErrorMetrics.NAME).tag("code", "CASE-2022-000057").counter()).isNull();
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_레지스트리가_던져도_밖으로_안_던진다() {
        ErrorMetrics broken = new ErrorMetrics(new ThrowingRegistry());

        assertThatCode(() -> broken.record("INVALID_REQUEST", 400)).doesNotThrowAnyException();
    }

    /** 카운터를 만들 때마다 던지는 레지스트리 — 지표 쪽 고장 흉내. */
    static class ThrowingRegistry extends SimpleMeterRegistry {
        @Override
        protected Counter newCounter(Meter.Id id) {
            throw new IllegalStateException("registry broken");
        }
    }
}
