package com.team.aml.shared.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 목록 쿼리 from·to 의 UTC Z 시각 검사 한 곳 (SCRUM-204 → AI 호출 기록·접근 기록이 같이 씀, SCRUM-168).
 * Instant 로 바로 받으면 숫자(20261108)를 epoch 밀리초로 읽고 +09:00 도 받아들여서 문자열 + 패턴으로 거른다.
 */
final class UtcZ {

    /** 2026-11-08T00:00:00Z · 2026-11-08T00:00:00.000Z. 월·일·시·분·초 범위까지 — 2월 30일 같은 건 parsesOrSkipped 가 막는다. */
    static final String PATTERN =
            "\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?Z";
    static final String MESSAGE = "UTC Z 시각이어야 합니다 (예: 2026-11-08T00:00:00Z)";

    private UtcZ() {}

    /** 패턴이 맞고 진짜 날짜일 때만 Instant, 아니면 null. */
    static Instant parseOrNull(String value) {
        if (value == null || !value.matches(PATTERN)) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 없음·패턴 틀림(@Pattern 이 잡음)은 통과, 패턴은 맞는데 없는 날짜만 false. */
    static boolean parsesOrSkipped(String value) {
        return value == null || !value.matches(PATTERN) || parseOrNull(value) != null;
    }

    /** from ≤ to. 둘 중 하나라도 없거나 못 읽으면 통과(다른 검사가 잡음). */
    static boolean inOrder(String from, String to) {
        Instant f = parseOrNull(from);
        Instant t = parseOrNull(to);
        return f == null || t == null || !f.isAfter(t);
    }
}
