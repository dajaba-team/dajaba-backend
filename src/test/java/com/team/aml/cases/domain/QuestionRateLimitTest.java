package com.team.aml.cases.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 질문하기 횟수 제한 판정 (설계 문서 7.4, SCRUM-302) — 순수 함수. */
class QuestionRateLimitTest {

    private static final Instant NOW = Instant.parse("2026-11-19T09:00:30Z");

    @Test
    void 정상_한도_미만이면_통과() {
        assertThatCode(() -> QuestionRateLimit.check("CASE-2022-000057", 5, 4, NOW.minusSeconds(50), NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuestionRateLimit.check("CASE-2022-000057", 5, 0, null, NOW)).doesNotThrowAnyException();
    }

    @Test
    void 실패_한도만큼_물었으면_429_가장_오래된_질문이_창을_벗어날_때까지_남은_초() {
        assertThatThrownBy(() -> QuestionRateLimit.check("CASE-2022-000057", 5, 5, NOW.minusSeconds(45), NOW))
                .isInstanceOfSatisfying(QuestionRateLimitedException.class, e -> {
                    assertThat(e.code()).isEqualTo("QUESTION_RATE_LIMITED");
                    assertThat(e.details()).containsEntry("retry_after_seconds", 15L)
                            .containsEntry("case_no", "CASE-2022-000057");
                });
    }

    @Test
    void 경계_남은_시간은_올림이고_최소_1초_최대_60초() {
        assertThat(retryAfter(NOW.minusMillis(59_001))).isEqualTo(1L);    // 0.999초 → 1
        assertThat(retryAfter(NOW.minusMillis(59_999))).isEqualTo(1L);    // 0.001초 → 1
        assertThat(retryAfter(NOW.minusMillis(30_500))).isEqualTo(30L);   // 29.5초 → 30
        assertThat(retryAfter(NOW)).isEqualTo(60L);
        assertThat(retryAfter(null)).isEqualTo(60L);                      // 못 찾으면 창 전체
        assertThat(retryAfter(NOW.minusSeconds(61))).isEqualTo(1L);       // 시계가 어긋나 이미 지났어도 최소 1
    }

    private static long retryAfter(Instant oldest) {
        try {
            QuestionRateLimit.check("CASE-2022-000057", 5, 5, oldest, NOW);
        } catch (QuestionRateLimitedException e) {
            return (Long) e.details().get("retry_after_seconds");
        }
        throw new AssertionError("429 여야 합니다");
    }
}
