package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R01 판정 (B7). DB 없이. */
class FanOutRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDateTime E = LocalDateTime.of(2022, 9, 2, 10, 9);
    private final FanOutRule rule = new FanOutRule(96, 10);

    @Test
    void 지금_N_이상이고_1분_전_N_미만이면_경보() {
        assertThat(rule.fires(new FanMinute("A", E, 10, 9))).isTrue();
        assertThat(rule.fires(new FanMinute("A", E, 12, 0))).isTrue();   // 같은 분에 한꺼번에
    }

    @Test
    void 참이_이어지면_경보_없음() {
        assertThat(rule.fires(new FanMinute("A", E, 11, 10))).isFalse();
    }

    @Test
    void 지금_N_미만이면_경보_없음() {
        assertThat(rule.fires(new FanMinute("A", E, 9, 8))).isFalse();
    }

    @Test
    void 창은_E더하기1분에서_window_hours_전부터() {
        assertThat(rule.windowEnd(E)).isEqualTo(LocalDateTime.of(2022, 9, 2, 10, 10));
        assertThat(rule.windowStart(E)).isEqualTo(LocalDateTime.of(2022, 8, 29, 10, 10));
    }

    @Test
    void 기준값은_params에서() throws Exception {
        assertThat(FanOutRule.from(OM.readTree("{\"window_hours\": 96, \"min_distinct_receivers\": 10}")))
                .isEqualTo(new FanOutRule(96, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}",
            "{\"window_hours\": 96}", "{\"min_distinct_receivers\": 10}",
            "{\"window_hours\": 96, \"min_distinct_receivers\": 0}", "{\"window_hours\": 0, \"min_distinct_receivers\": 10}",
            "{\"window_hours\": 96, \"min_distinct_receivers\": \"10\"}", "{\"window_hours\": \"96\", \"min_distinct_receivers\": 10}",
            "{\"window_hours\": 96, \"min_distinct_receivers\": 10.5}", "{\"window_hours\": 96.5, \"min_distinct_receivers\": 10}",
            "{\"window_hours\": 96, \"min_distinct_senders\": 10}"})   // R02 키를 잘못 넣은 경우
    void 기준값이_없거나_잘못되면_INVALID_RULE_PARAMS(String params) {
        assertThatThrownBy(() -> FanOutRule.from(OM.readTree(params)))
                .isInstanceOf(InvalidRuleParamsException.class);
    }
}
