package com.team.aml.shared.api;

import com.team.aml.shared.audit.AccessLogAction;
import com.team.aml.shared.audit.AccessLogQuery;
import com.team.aml.shared.audit.TargetType;
import com.team.aml.shared.domain.InvalidRequestException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.BindParam;

/**
 * GET /admin/access-logs 쿼리 (SCRUM-168). 형식 검사는 여기서 끝낸다 — AccessLogQuery 는 검사된 값만 받는다.
 * 틀리면 400 INVALID_REQUEST. from·to 는 AI 호출 기록과 같은 UtcZ 검사(오류 키 from·to·real_dates·period_in_order).
 * target_id 는 화면 번호 — target_type 과 같이 줄 때만(target_id_with_type), 종류에 맞는 형식이 아니면 details.target_id.
 */
public record AccessLogListQuery(
        @Parameter(name = "login_id", description = "행위자 아이디") @BindParam("login_id")
        @Pattern(regexp = LOGIN_ID, message = "영문·숫자·_·- 3~64자") String loginId,
        AccessLogAction action,
        @Parameter(name = "target_type") @BindParam("target_type") TargetType targetType,
        @Parameter(name = "target_id", description = "화면 번호 (CASE-2022-000057 · DRF-000123 · ALT-20220902-000142 · 아이디)")
        @BindParam("target_id") String targetId,
        @Parameter(description = "UTC Z만, 포함", example = "2026-11-08T00:00:00Z")
        @Pattern(regexp = UtcZ.PATTERN, message = UtcZ.MESSAGE) String from,
        @Parameter(description = "UTC Z만, 제외", example = "2026-11-09T00:00:00Z")
        @Pattern(regexp = UtcZ.PATTERN, message = UtcZ.MESSAGE) String to,
        @Pattern(regexp = "created_at,(asc|desc)") String sort,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    static final String DEFAULT_SORT = "created_at,desc";
    static final int DEFAULT_SIZE = 20;

    /** 계정 발급(CreateUserRequest)과 같은 아이디 규칙. */
    static final String LOGIN_ID = "^[A-Za-z0-9_-]{3,64}$";

    @Schema(hidden = true)
    @AssertTrue(message = "from 은 to 보다 늦을 수 없습니다")
    public boolean isPeriodInOrder() {
        return UtcZ.inOrder(from, to);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "from·to 에 없는 날짜가 있습니다")
    public boolean isRealDates() {
        return UtcZ.parsesOrSkipped(from) && UtcZ.parsesOrSkipped(to);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "target_id 는 target_type 과 같이 보내야 합니다")
    public boolean isTargetIdWithType() {
        return targetId == null || targetType != null;
    }

    /** @Valid 를 통과한 뒤에 부른다. target_id 형식은 target_type 을 알아야 봐서 여기서 — 틀리면 400 details.target_id. */
    AccessLogQuery.Filter toFilter() {
        if (targetId != null && !targetId.matches(targetPattern(targetType))) {
            throw new InvalidRequestException("target_id",
                    "%s 번호 형식이 아닙니다 (예: %s)".formatted(targetType, example(targetType)));
        }
        return new AccessLogQuery.Filter(loginId, action, targetType, targetId,
                UtcZ.parseOrNull(from), UtcZ.parseOrNull(to));
    }

    /** CASE 는 V006 cases_case_no_ck 와 같음. 숫자 자리 상한 18은 DRF 숫자 파싱이 long 을 넘지 않게. */
    private static String targetPattern(TargetType type) {
        return switch (type) {
            case CASE -> "^CASE-\\d{4}-\\d{6,18}$";
            case STR_DRAFT -> "^DRF-\\d{6,18}$";
            case ALERT -> "^ALT-\\d{8}-\\d{6,18}$";
            case USER -> LOGIN_ID;
        };
    }

    private static String example(TargetType type) {
        return switch (type) {
            case CASE -> "CASE-2022-000057";
            case STR_DRAFT -> "DRF-000123";
            case ALERT -> "ALT-20220902-000142";
            case USER -> "inv01";
        };
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
