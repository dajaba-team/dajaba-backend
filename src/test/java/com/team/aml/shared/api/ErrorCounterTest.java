package com.team.aml.shared.api;

import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.NotFoundException;
import com.team.aml.shared.metrics.ErrorMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 가 에러 응답을 만들 때 aml.http.errors 를 code 별로 1씩 센다 (03_shared §13, SCRUM-271).
 * 보안 필터 쪽(401·403·CSRF)은 MetricsEndpointTest 가 실제 필터 체인으로 본다.
 */
class ErrorCounterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MockMvc mvc = mvcWith(new ErrorMetrics(registry));

    private static MockMvc mvcWith(ErrorMetrics metrics) {
        return MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(metrics))
                .build();
    }

    private double count(String code, String status) {
        Counter c = registry.find(ErrorMetrics.NAME).tag("code", code).tag("status", status).counter();
        return c == null ? 0 : c.count();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_409_400_404를_code별로_1씩_센다() throws Exception {
        mvc.perform(get("/t/transition")).andExpect(status().isConflict());
        mvc.perform(get("/t/required")).andExpect(status().isBadRequest());
        mvc.perform(get("/t/case")).andExpect(status().isNotFound());

        assertThat(count("INVALID_CASE_TRANSITION", "409")).isEqualTo(1);
        assertThat(count("INVALID_REQUEST", "400")).isEqualTo(1);
        assertThat(count("CASE_NOT_FOUND", "404")).isEqualTo(1);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_표에_없는_AI_코드는_응답엔_그대로_지표엔_AI_OTHER() throws Exception {
        mvc.perform(get("/t/ai"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("BRAND_NEW_AI_CODE"));   // 응답 모양은 안 바뀐다

        assertThat(count("AI_OTHER", "502")).isEqualTo(1);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_지표가_고장나도_에러_응답은_그대로() throws Exception {
        MockMvc broken = mvcWith(new ErrorMetrics(new ThrowingRegistry()));

        broken.perform(get("/t/transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_CASE_TRANSITION"))
                .andExpect(jsonPath("$.error.details.from").value("OPEN"));
    }

    // --- 테스트 전용 ---

    /** 카운터를 만들 때마다 던지는 레지스트리 — 지표 쪽 고장 흉내. */
    static class ThrowingRegistry extends SimpleMeterRegistry {
        @Override
        protected Counter newCounter(Meter.Id id) {
            throw new IllegalStateException("registry broken");
        }
    }

    static class InvalidCaseTransitionException extends DomainException {
        InvalidCaseTransitionException() { super("상태 충돌", Map.of("from", "OPEN", "to", "ESCALATED")); }
    }

    static class CaseNotFoundException extends NotFoundException {
        CaseNotFoundException() { super("없음", Map.of()); }
    }

    @RestController
    static class FailingController {

        @GetMapping("/t/transition")
        void transition() {
            throw new InvalidCaseTransitionException();
        }

        @GetMapping("/t/required")
        void required(@RequestParam("as_of") String asOf) {
        }

        @GetMapping("/t/case")
        void caseNotFound() {
            throw new CaseNotFoundException();
        }

        @GetMapping("/t/ai")
        void ai() {
            throw new AiClientException("BRAND_NEW_AI_CODE", "새 코드");
        }
    }
}
