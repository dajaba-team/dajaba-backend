package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R04 판정 — E 마다 지금 창·1분 전 창 비교 (rules.md R04). DB 없이. */
class CycleRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDate D = LocalDate.of(2022, 9, 5);
    private final CycleRule rule = new CycleRule(96, 4);

    /** A→B(t0) → B→A(t1) 2홉 고리. */
    private static CyclePath cycle(long id, LocalDateTime t0, LocalDateTime t1) {
        return new CyclePath(List.of(id, id + 1), List.of(t0, t1), List.of("A", "B"), List.of("B", "A"));
    }

    @Test
    void 그날_처음_닫힌_고리에_경보_1건() {
        CyclePath c = cycle(1, D.atTime(9, 0), D.atTime(10, 0));
        List<CycleAlert> alerts = rule.alerts(List.of(c), List.of(), D);
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.minute()).isEqualTo(D.atTime(10, 0));
            assertThat(a.paths()).containsExactly(c);
        });
    }

    @Test
    void 고리가_남아_있는_동안_새로_닫힌_고리는_경보를_늘리지_않는다() {
        List<CycleAlert> alerts = rule.alerts(List.of(
                cycle(1, D.atTime(9, 0), D.atTime(10, 0)),
                cycle(3, D.atTime(11, 0), D.atTime(12, 0))), List.of(), D);
        assertThat(alerts).singleElement().satisfies(a -> assertThat(a.minute()).isEqualTo(D.atTime(10, 0)));
    }

    @Test
    void 전날_닫힌_고리가_1분_전_창에_있으면_경보_없음() {
        CycleMark yesterday = new CycleMark("A", D.minusDays(1).atTime(10, 0), D.minusDays(1).atTime(9, 0));
        assertThat(rule.alerts(List.of(cycle(3, D.atTime(11, 0), D.atTime(12, 0))), List.of(yesterday), D)).isEmpty();
    }

    @Test
    void 전날_고리_요약은_가장_늦은_첫_거래_시각_하나로_판단() {
        LocalDateTime e = D.atTime(12, 0);
        CycleMark justIn = new CycleMark("A", D.minusDays(1).atTime(10, 0), e.minusHours(96));             // 첫 거래 = E-H → 1분 전 창 안
        CycleMark justOut = new CycleMark("A", D.minusDays(1).atTime(10, 0), e.minusHours(96).minusMinutes(1));
        CyclePath fresh = cycle(3, D.atTime(11, 0), e);
        assertThat(rule.alerts(List.of(fresh), List.of(justIn), D)).isEmpty();
        assertThat(rule.alerts(List.of(fresh), List.of(justOut), D)).hasSize(1);
    }

    @Test
    void 전날_고리가_창_밖으로_나갔으면_다시_경보() {
        CycleMark old = new CycleMark("A", D.minusDays(4).atTime(10, 0), D.minusDays(4).atTime(9, 0));   // 첫 거래가 E-H 이전
        CyclePath fresh = cycle(3, D.atTime(11, 0), D.atTime(12, 0));
        assertThat(rule.alerts(List.of(fresh), List.of(old), D)).singleElement()
                .satisfies(a -> assertThat(a.paths()).containsExactly(fresh));
    }

    @Test
    void 경보의_paths는_E_시점_창_안의_고리_전부_첫_거래_시각순() {
        CyclePath early = cycle(1, D.atTime(8, 0), D.atTime(10, 0));
        CyclePath sameClose = cycle(3, D.atTime(7, 0), D.atTime(10, 0));
        assertThat(rule.alerts(List.of(early, sameClose), List.of(), D)).singleElement()
                .satisfies(a -> assertThat(a.paths()).containsExactly(sameClose, early));
    }

    @Test
    void 창은_E더하기1분에서_window_hours_전부터() {
        LocalDateTime e = D.atTime(10, 9);
        assertThat(rule.windowEnd(e)).isEqualTo(D.atTime(10, 10));
        assertThat(rule.windowStart(e)).isEqualTo(D.minusDays(4).atTime(10, 10));
    }

    @Test
    void 기준값은_params에서() throws Exception {
        assertThat(CycleRule.from(OM.readTree("{\"window_hours\": 96, \"max_hops\": 4}"))).isEqualTo(new CycleRule(96, 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"window_hours\": 96}", "{\"max_hops\": 4}",
            "{\"window_hours\": 96, \"max_hops\": 1}", "{\"window_hours\": 96, \"max_hops\": 5}",
            "{\"window_hours\": 96, \"max_hops\": \"4\"}", "{\"window_hours\": 0, \"max_hops\": 4}"})
    void 기준값이_없거나_잘못되면_INVALID_RULE_PARAMS(String params) {
        assertThatThrownBy(() -> CycleRule.from(OM.readTree(params))).isInstanceOf(InvalidRuleParamsException.class);
    }
}
