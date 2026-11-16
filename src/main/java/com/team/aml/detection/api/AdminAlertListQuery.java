package com.team.aml.detection.api;

import com.team.aml.detection.domain.AlertReviewFilter;
import com.team.aml.detection.domain.AlertReviewOrder;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;

import java.time.LocalDate;

/** GET /admin/alerts 쿼리 (R1). 형식 검사는 여기서 끝낸다 — 틀리면 400 INVALID_REQUEST. date 는 경보 배치일(YYYY-MM-DD). */
public record AdminAlertListQuery(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @Parameter(name = "rule_id", example = "R02_FAN_IN") @BindParam("rule_id")
        @Pattern(regexp = "^[A-Z0-9_]{1,32}$", message = "룰 ID 형식이 아닙니다") String ruleId,
        @Parameter(name = "score_status") @BindParam("score_status") ScoreStatus scoreStatus,
        @Parameter(name = "score_failure_code") @BindParam("score_failure_code") ScoreFailureCode scoreFailureCode,
        @Parameter(name = "case_opened") @BindParam("case_opened") Boolean caseOpened,
        @Pattern(regexp = "(alert_no|score|created_at),(asc|desc)") String sort,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    static final int DEFAULT_SIZE = 20;

    /** alerts.score_status (V005 CHECK). */
    public enum ScoreStatus { PENDING, SCORED, FAILED }

    /** alerts.score_failure_code (V005 CHECK). */
    public enum ScoreFailureCode { TRANSACTION_NOT_FOUND, HISTORY_NOT_SUPPORTED, REQUIRED_INPUT_MISSING, COMPUTE_ERROR }

    AlertReviewFilter toFilter() {
        return new AlertReviewFilter(date, ruleId, scoreStatus == null ? null : scoreStatus.name(),
                scoreFailureCode == null ? null : scoreFailureCode.name(), caseOpened);
    }

    AlertReviewOrder order() {
        return switch (sort == null ? "score,desc" : sort) {
            case "score,asc" -> AlertReviewOrder.SCORE_ASC;
            case "alert_no,asc" -> AlertReviewOrder.ALERT_NO_ASC;
            case "alert_no,desc" -> AlertReviewOrder.ALERT_NO_DESC;
            case "created_at,asc" -> AlertReviewOrder.CREATED_AT_ASC;
            case "created_at,desc" -> AlertReviewOrder.CREATED_AT_DESC;
            default -> AlertReviewOrder.SCORE_DESC;
        };
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
