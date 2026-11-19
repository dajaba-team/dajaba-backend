package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R03 판정·쌍 조건 (rules.md R03). DB 없이. 환율 1 USD = 0.8534 EUR (V016 표). */
class FxRelayRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDateTime A = LocalDateTime.of(2022, 9, 2, 10, 0);
    private static final LocalDateTime B = LocalDateTime.of(2022, 9, 2, 11, 0);
    private final FxRelayRule rule = new FxRelayRule(96, new BigDecimal("0.1"), true,
            Map.of("US Dollar", BigDecimal.ONE, "Euro", new BigDecimal("0.8534")));

    private static RelayPair pair(String inCur, String inAmt, String outCur, String outAmt) {
        return new RelayPair(1, A, "M", inCur, new BigDecimal(inAmt), 2, B, "M", outCur, new BigDecimal(outAmt));
    }

    @Test
    void 통화가_바뀌고_USD_환산이_같으면_중계() {
        assertThat(rule.relays(pair("US Dollar", "100", "Euro", "85.34"))).isTrue();
        assertThat(rule.ratio(pair("US Dollar", "100", "Euro", "85.34"))).isEqualByComparingTo("1");
    }

    @Test
    void 비율_0_9와_1_1은_양_끝_포함() {
        assertThat(rule.relays(pair("US Dollar", "100", "Euro", "76.806"))).isTrue();    // 0.9
        assertThat(rule.relays(pair("US Dollar", "100", "Euro", "93.874"))).isTrue();    // 1.1
        assertThat(rule.relays(pair("US Dollar", "100", "Euro", "76.805"))).isFalse();
        assertThat(rule.relays(pair("US Dollar", "100", "Euro", "93.875"))).isFalse();
    }

    @Test
    void 같은_통화이거나_표에_없는_통화이거나_받은_금액이_0이면_중계_아님() {
        assertThat(rule.relays(pair("US Dollar", "100", "US Dollar", "100"))).isFalse();
        assertThat(rule.relays(pair("Dinar", "100", "Euro", "85.34"))).isFalse();
        assertThat(rule.relays(pair("US Dollar", "0", "Euro", "0"))).isFalse();
    }

    @Test
    void 통화_변경이_필수가_아니면_같은_통화도_중계() {
        FxRelayRule loose = new FxRelayRule(96, new BigDecimal("0.1"), false, Map.of("US Dollar", BigDecimal.ONE));
        assertThat(loose.relays(pair("US Dollar", "100", "US Dollar", "105"))).isTrue();
    }

    @Test
    void 경보는_1분_전_창에_쌍이_없을_때만() {
        assertThat(rule.fires(new FxRelayMinute("M", B, false))).isTrue();
        assertThat(rule.fires(new FxRelayMinute("M", B, true))).isFalse();
    }

    @Test
    void 창은_E더하기1분에서_window_hours_전부터() {
        assertThat(rule.windowEnd(B)).isEqualTo(LocalDateTime.of(2022, 9, 2, 11, 1));
        assertThat(rule.windowStart(B)).isEqualTo(LocalDateTime.of(2022, 8, 29, 11, 1));
    }

    @Test
    void 기준값은_params에서_V016_모양() throws Exception {
        FxRelayRule r = FxRelayRule.from(OM.readTree("""
                {"window_hours": 96, "amount_tolerance": 0.1, "require_currency_change": true,
                 "usd_rates": {"US Dollar": 1, "Euro": 0.8534, "Bitcoin": 0.00008421}}"""));
        assertThat(r.windowHours()).isEqualTo(96);
        assertThat(r.amountTolerance()).isEqualByComparingTo("0.1");
        assertThat(r.requireCurrencyChange()).isTrue();
        assertThat(r.usdRates()).hasSize(3);
        assertThat(r.usdRates().get("Bitcoin")).isEqualByComparingTo("0.00008421");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}",
            "{\"window_hours\": 96, \"amount_tolerance\": 0.1, \"require_currency_change\": true}",
            "{\"window_hours\": 96, \"amount_tolerance\": 0.1, \"require_currency_change\": true, \"usd_rates\": {}}",
            "{\"window_hours\": 96, \"amount_tolerance\": 0.1, \"require_currency_change\": true, \"usd_rates\": {\"Euro\": 0}}",
            "{\"window_hours\": 96, \"amount_tolerance\": 0.1, \"require_currency_change\": true, \"usd_rates\": {\"Euro\": \"0.85\"}}",
            "{\"window_hours\": 96, \"amount_tolerance\": -0.1, \"require_currency_change\": true, \"usd_rates\": {\"Euro\": 0.85}}",
            "{\"window_hours\": 96, \"amount_tolerance\": 1, \"require_currency_change\": true, \"usd_rates\": {\"Euro\": 0.85}}",
            "{\"window_hours\": 96, \"amount_tolerance\": \"0.1\", \"require_currency_change\": true, \"usd_rates\": {\"Euro\": 0.85}}",
            "{\"window_hours\": 96, \"amount_tolerance\": 0.1, \"require_currency_change\": \"true\", \"usd_rates\": {\"Euro\": 0.85}}",
            "{\"amount_tolerance\": 0.1, \"require_currency_change\": true, \"usd_rates\": {\"Euro\": 0.85}}"})
    void 기준값이_없거나_잘못되면_INVALID_RULE_PARAMS(String params) {
        assertThatThrownBy(() -> FxRelayRule.from(OM.readTree(params)))
                .isInstanceOf(InvalidRuleParamsException.class);
    }
}
