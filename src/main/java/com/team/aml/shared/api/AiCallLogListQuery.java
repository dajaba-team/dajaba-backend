package com.team.aml.shared.api;

import com.team.aml.shared.ai.AiCallLogQuery;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * GET /admin/ai-calls 쿼리 (SCRUM-134). 형식 검사는 여기서 끝낸다 — 틀리면 400 INVALID_REQUEST (CaseListQuery 와 같은 틀).
 * 쿼리 이름은 snake_case 라 @BindParam 으로 묶고, swagger 이름은 @Parameter 로 맞춘다.
 * from·to 는 created_at 범위(from 포함·to 제외), UTC Z 만 — 문자열로 받아 패턴으로 거른다(SCRUM-204).
 * Instant 로 바로 받으면 숫자(20261108)를 epoch 밀리초로 읽고 +09:00 도 받아들여서다. target_id 는 target_type 과 같이 줄 때만.
 */
public record AiCallLogListQuery(
        @Size(min = 1, max = 64) String endpoint,
        @Parameter(name = "request_id") @BindParam("request_id") @Size(min = 1, max = 64) String requestId,
        @Parameter(name = "target_type") @BindParam("target_type") @Pattern(regexp = "BATCH_RUN|CASE|DRAFT") String targetType,
        @Parameter(name = "target_id") @BindParam("target_id") @Size(min = 1, max = 64) String targetId,
        @Parameter(name = "error_code") @BindParam("error_code") @Size(min = 1, max = 32) String errorCode,
        AiCallLogQuery.Outcome outcome,
        @Parameter(description = "UTC Z만, 포함", example = "2026-11-08T00:00:00Z")
        @Pattern(regexp = UTC_Z, message = UTC_Z_MESSAGE) String from,
        @Parameter(description = "UTC Z만, 제외", example = "2026-11-09T00:00:00Z")
        @Pattern(regexp = UTC_Z, message = UTC_Z_MESSAGE) String to,
        @Pattern(regexp = "created_at,(asc|desc)") String sort,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    static final String DEFAULT_SORT = "created_at,desc";
    static final int DEFAULT_SIZE = 20;

    /** 2026-11-08T00:00:00Z · 2026-11-08T00:00:00.000Z. 월·일·시·분·초 범위까지 — 2월 30일 같은 건 isRealDates 가 막는다. */
    static final String UTC_Z =
            "\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?Z";
    static final String UTC_Z_MESSAGE = "UTC Z 시각이어야 합니다 (예: 2026-11-08T00:00:00Z)";

    /** 검증 전용(from ≤ to). 쿼리 파라미터가 아니라서 swagger 에 숨긴다. */
    @Schema(hidden = true)
    @AssertTrue(message = "from 은 to 보다 늦을 수 없습니다")
    public boolean isPeriodInOrder() {
        Instant f = parseOrNull(from);
        Instant t = parseOrNull(to);
        return f == null || t == null || !f.isAfter(t);
    }

    /** 검증 전용 — 패턴은 맞지만 없는 날짜(2026-02-30 등). 패턴이 틀린 값은 @Pattern 이 잡으니 여기선 통과. */
    @Schema(hidden = true)
    @AssertTrue(message = "from·to 에 없는 날짜가 있습니다")
    public boolean isRealDates() {
        return parsesOrSkipped(from) && parsesOrSkipped(to);
    }

    /** 검증 전용 — target_id 만 오면 어떤 대상인지 몰라 거절한다. */
    @Schema(hidden = true)
    @AssertTrue(message = "target_id 는 target_type 과 같이 보내야 합니다")
    public boolean isTargetIdWithType() {
        return targetId == null || targetType != null;
    }

    AiCallLogQuery.Filter toFilter() {
        return new AiCallLogQuery.Filter(endpoint, requestId, targetType, targetId, errorCode, outcome,
                parseOrNull(from), parseOrNull(to));
    }

    /** 패턴이 맞고 진짜 날짜일 때만 Instant, 아니면 null (검증을 통과한 뒤에만 toFilter 가 불린다). */
    private static Instant parseOrNull(String value) {
        if (value == null || !value.matches(UTC_Z)) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean parsesOrSkipped(String value) {
        return value == null || !value.matches(UTC_Z) || parseOrNull(value) != null;
    }

    String sortOrDefault() {
        return sort == null ? DEFAULT_SORT : sort;
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
