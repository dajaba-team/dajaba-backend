package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R06 판정 (B7). DB 없이. 하한 5·상한 100 (V016). */
class GatherScatterRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDateTime E = LocalDateTime.of(2022, 9, 2, 10, 9);
    private final GatherScatterRule rule = new GatherScatterRule(96, 5, 100, 5, 100);

    private static GatherScatterMinute m(int sNow, int rNow, int sBefore, int rBefore) {
        return new GatherScatterMinute("H", E, sNow, rNow, sBefore, rBefore);
    }

    @Test
    void 둘_다_하한에_닿는_분에_경보() {
        assertThat(rule.fires(m(5, 5, 5, 4))).isTrue();     // 수신 쪽이 마지막으로 채워짐
        assertThat(rule.fires(m(5, 5, 4, 5))).isTrue();     // 송신 쪽이 마지막으로 채워짐
        assertThat(rule.fires(m(100, 100, 0, 0))).isTrue(); // 상한 포함
    }

    @Test
    void 한쪽만_하한이면_경보_없음() {
        assertThat(rule.fires(m(5, 4, 0, 0))).isFalse();
        assertThat(rule.fires(m(30, 0, 0, 0))).isFalse();
    }

    @Test
    void 상한을_넘으면_참이_아님() {
        assertThat(rule.fires(m(101, 5, 4, 5))).isFalse();
        assertThat(rule.fires(m(5, 101, 5, 4))).isFalse();
    }

    @Test
    void 참이_이어지면_경보_없음() {
        assertThat(rule.fires(m(6, 7, 5, 7))).isFalse();
    }

    @Test
    void 거래가_있는_분에_1분_전_창이_상한_초과였고_지금_안으로_들어오면_경보() {
        assertThat(rule.fires(m(100, 5, 101, 5))).isTrue();
    }

    @Test
    void 창은_E더하기1분에서_window_hours_전부터() {
        assertThat(rule.windowEnd(E)).isEqualTo(LocalDateTime.of(2022, 9, 2, 10, 10));
        assertThat(rule.windowStart(E)).isEqualTo(LocalDateTime.of(2022, 8, 29, 10, 10));
    }

    @Test
    void 기준값은_params에서() throws Exception {
        assertThat(GatherScatterRule.from(OM.readTree("""
                {"window_hours": 96, "min_distinct_senders": 5, "max_distinct_senders": 100,
                 "min_distinct_receivers": 5, "max_distinct_receivers": 100}""")))
                .isEqualTo(new GatherScatterRule(96, 5, 100, 5, 100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}",
            "{\"window_hours\": 96, \"min_distinct_senders\": 5, \"max_distinct_senders\": 100, \"min_distinct_receivers\": 5}",
            "{\"window_hours\": 96, \"min_distinct_senders\": 0, \"max_distinct_senders\": 100, \"min_distinct_receivers\": 5, \"max_distinct_receivers\": 100}",
            "{\"window_hours\": 96, \"min_distinct_senders\": 5, \"max_distinct_senders\": 4, \"min_distinct_receivers\": 5, \"max_distinct_receivers\": 100}",
            "{\"window_hours\": 96, \"min_distinct_senders\": 5, \"max_distinct_senders\": 100, \"min_distinct_receivers\": 5, \"max_distinct_receivers\": 4}",
            "{\"window_hours\": 96, \"min_distinct_senders\": \"5\", \"max_distinct_senders\": 100, \"min_distinct_receivers\": 5, \"max_distinct_receivers\": 100}",
            "{\"window_hours\": 96.5, \"min_distinct_senders\": 5, \"max_distinct_senders\": 100, \"min_distinct_receivers\": 5, \"max_distinct_receivers\": 100}"})
    void 기준값이_없거나_잘못되면_INVALID_RULE_PARAMS(String params) {
        assertThatThrownBy(() -> GatherScatterRule.from(OM.readTree(params)))
                .isInstanceOf(InvalidRuleParamsException.class);
    }
}
