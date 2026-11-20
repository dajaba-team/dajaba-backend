package com.team.aml.shared.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 에러 코드별 건수 aml.http.errors{code, status} (03_shared §13, SCRUM-271).
 * 에러 응답을 쓰는 두 곳에서 부른다 — GlobalExceptionHandler(컨트롤러 안 예외)와 JsonAuthHandlers(보안 필터의 401·403·CSRF·로그인).
 *
 * 태그 값은 정해진 것만 (NFR-COM-04):
 *  - code 가 대문자·숫자·_ 형식(2~64자)이 아니면 OTHER
 *  - 502 는 AI 가 준 코드를 그대로 내보내는 자리라, 03_shared §2 표에 있는 AI 코드가 아니면 AI_OTHER
 * 세다가 예외가 나도 밖으로 던지지 않는다(fail-open) — 에러 응답은 그대로 나가야 한다.
 */
@Component
public class ErrorMetrics {

    public static final String NAME = "aml.http.errors";

    private static final Logger log = LoggerFactory.getLogger(ErrorMetrics.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final int AI_PASS_THROUGH_STATUS = 502;

    /** 03_shared §2 표의 502 줄 — AI 규약 §6.3 코드 + 백엔드가 붙인 AI_ 코드. 새 AI 코드는 표에 먼저 넣고 여기에. */
    private static final Set<String> AI_CODES = Set.of(
            "LLM_UNAVAILABLE", "LLM_INVALID_OUTPUT", "LLM_BUDGET_EXCEEDED", "MODEL_UNAVAILABLE", "DB_UNAVAILABLE",
            "AI_UNAVAILABLE", "AI_TIMEOUT", "AI_BAD_RESPONSE",
            "AI_DRAFT_NOT_FOUND", "AI_INVALID_REQUEST", "AI_UNAUTHENTICATED",
            "SUMMARY_CODE_CHECK_FAILED");

    private final MeterRegistry registry;

    public ErrorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String code, int status) {
        try {
            registry.counter(NAME, "code", tagCode(code, status), "status", String.valueOf(status)).increment();
        } catch (RuntimeException e) {
            log.warn("error metric failed error_type={}", e.getClass().getSimpleName());
        }
    }

    /**
     * AI 가 준 코드를 지표 태그 값으로 — 위 AI 코드 표에 없으면 AI_OTHER, 형식이 아니면 OTHER.
     * 기능 지표(예 aml.cases.summary.refreshes, SCRUM-276)가 AI 코드를 태그로 쓸 때 표를 하나만 두려고 연다.
     */
    public static String aiCodeTag(String code) {
        return tagCode(code, AI_PASS_THROUGH_STATUS);
    }

    static String tagCode(String code, int status) {
        if (code == null || !CODE.matcher(code).matches()) {
            return "OTHER";
        }
        if (status == AI_PASS_THROUGH_STATUS && !AI_CODES.contains(code)) {
            return "AI_OTHER";
        }
        return code;
    }
}
