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

/** R04 판정 — 사건형, (A, 닫힌 분 E) 마다 경보 (rules.md R04). DB 없이. */
class CycleRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDate D = LocalDate.of(2022, 9, 5);
    private final CycleRule rule = new CycleRule(96, 4);

    /** A→B(t0, id) → B→A(t1, id+1) 2홉 고리. */
    private static CyclePath cycle(long id, LocalDateTime t0, LocalDateTime t1) {
        return cycle(id, t0, id + 1, t1);
    }

    /** A→B(t0, out) → B→A(t1, back) 2홉 고리 — 같은 거래를 여러 고리에 쓸 때. */
    private static CyclePath cycle(long out, LocalDateTime t0, long back, LocalDateTime t1) {
        return new CyclePath(List.of(out, back), List.of(t0, t1), List.of("A", "B"), List.of("B", "A"));
    }

    @Test
    void 그날_처음_닫힌_고리에_경보_1건() {
        CyclePath c = cycle(1, D.atTime(9, 0), D.atTime(10, 0));
        assertThat(rule.alerts(List.of(c), D)).singleElement().satisfies(a -> {
            assertThat(a.minute()).isEqualTo(D.atTime(10, 0));
            assertThat(a.paths()).containsExactly(c);
            assertThat(a.valid(rule)).isTrue();
        });
    }

    @Test
    void 앞_고리가_창에_남아_있어도_다른_분에_닫힌_고리는_각각_경보() {   // paths 는 자기 분에 닫힌 고리만
        CyclePath c1 = cycle(1, D.atTime(9, 0), D.atTime(10, 0));
        CyclePath c2 = cycle(3, D.atTime(11, 0), D.atTime(12, 0));
        assertThat(rule.alerts(List.of(c2, c1), D)).satisfiesExactly(
                a -> {
                    assertThat(a.minute()).isEqualTo(D.atTime(10, 0));
                    assertThat(a.paths()).containsExactly(c1);
                },
                a -> {
                    assertThat(a.minute()).isEqualTo(D.atTime(12, 0));
                    assertThat(a.paths()).containsExactly(c2);
                });
    }

    @Test
    void 이미_경보에_쓴_거래도_나중에_닫힌_고리의_일부로_다시_쓰인다() {
        // A→B 09:00(1), B→A 10:00(2), A→B 11:00(3), B→A 12:00(4) — 12:00 에 (09:00, 12:00)·(11:00, 12:00) 두 고리가 닫힌다
        CyclePath at10 = cycle(1, D.atTime(9, 0), 2, D.atTime(10, 0));
        CyclePath at12Old = cycle(1, D.atTime(9, 0), 4, D.atTime(12, 0));
        CyclePath at12New = cycle(3, D.atTime(11, 0), 4, D.atTime(12, 0));
        assertThat(rule.alerts(List.of(at12New, at10, at12Old), D)).satisfiesExactly(
                a -> {
                    assertThat(a.minute()).isEqualTo(D.atTime(10, 0));
                    assertThat(a.paths()).containsExactly(at10);
                },
                a -> {
                    assertThat(a.minute()).isEqualTo(D.atTime(12, 0));
                    assertThat(a.paths()).containsExactly(at12Old, at12New);   // 첫 거래 시각순
                    assertThat(a.transactionIds()).containsExactly(1L, 3L, 4L);
                    assertThat(a.valid(rule)).isTrue();
                });
    }

    @Test
    void 같은_분에_닫힌_고리_여럿은_경보_1건_paths는_첫_거래_시각순() {
        CyclePath early = cycle(1, D.atTime(8, 0), D.atTime(10, 0));
        CyclePath earlier = cycle(3, D.atTime(7, 0), D.atTime(10, 0));
        assertThat(rule.alerts(List.of(early, earlier), D)).singleElement()
                .satisfies(a -> assertThat(a.paths()).containsExactly(earlier, early));
    }

    @Test
    void 같은_거래를_여러_고리가_쓰면_근거_거래는_한_번만() {   // A→B 10:00·10:01, B→A 10:05 → 2홉 고리 2개, 근거 3건
        LocalDateTime back = D.atTime(10, 5);
        CyclePath p1 = cycle(1, D.atTime(10, 0), 3, back);
        CyclePath p2 = cycle(2, D.atTime(10, 1), 3, back);
        assertThat(rule.alerts(List.of(p2, p1), D)).singleElement().satisfies(a -> {
            assertThat(a.paths()).containsExactly(p1, p2);
            assertThat(a.transactionIds()).containsExactly(1L, 2L, 3L);
            assertThat(a.toRuleFacts().get("hops_by_path").toString()).isEqualTo("[2,2]");
        });
    }

    @Test
    void 다른_날_닫힌_고리는_그날_경보에_안_들어간다() {   // 이미 닫힌 고리는 다음 날 다시 경보가 되지 않음
        CyclePath yesterday = cycle(1, D.minusDays(1).atTime(9, 0), D.minusDays(1).atTime(10, 0));
        assertThat(rule.alerts(List.of(yesterday), D)).isEmpty();
    }

    @Test
    void 다른_분에_닫힌_고리가_섞이면_저장_전_검사_실패() {
        CyclePath c1 = cycle(1, D.atTime(9, 0), D.atTime(10, 0));
        CyclePath c2 = cycle(3, D.atTime(11, 0), D.atTime(12, 0));
        assertThat(new CycleAlert(D.atTime(12, 0), List.of(c1, c2)).valid(rule)).isFalse();
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
