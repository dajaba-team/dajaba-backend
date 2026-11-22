package com.team.aml.cases.api;

import com.team.aml.shared.domain.InvalidRequestException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * GET /notifications 쿼리 (SCRUM-355). since 는 Z 로 끝나는 UTC 시각만(★A 11/22 — 오프셋·날짜만·RFC 1123 은 400),
 * 초 아래 자리는 허용. size 1~50, 기본 20. 둘 다 위반이면 400 INVALID_REQUEST details.since·size.
 */
public record NotificationQuery(
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z",
                message = "since 는 UTC 시각(예: 2026-11-24T02:10:00Z)입니다") String since,
        @Min(1) @Max(50) Integer size) {

    /** 모양은 맞는데 없는 날짜(13월 등)면 400 details.since. */
    Instant sinceOrNull() {
        if (since == null) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("since", "since 는 UTC 시각(예: 2026-11-24T02:10:00Z)입니다");
        }
    }

    int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
