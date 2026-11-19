package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R05 판정·대표 경로·근거 (rules.md R05·2.1). DB 없이. N = 3. */
class ScatterGatherRuleTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final LocalDate D = LocalDate.of(2022, 9, 5);
    private final ScatterGatherRule rule = new ScatterGatherRule(96, 3);
    private long seq = 0;

    private ScatterGatherLeg out(String s, String m, LocalDateTime ts) {
        return new ScatterGatherLeg(s, "T", false, ++seq, ts, s, m);
    }

    private ScatterGatherLeg in(String s, String m, LocalDateTime ts) {
        return new ScatterGatherLeg(s, "T", true, ++seq, ts, m, "T");
    }

    /** S → M0..Mk-1 (09:0i), Mi → T (10:0i). */
    private List<ScatterGatherLeg> fan(String s, int k, LocalDate day) {
        List<ScatterGatherLeg> legs = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            legs.add(out(s, s + "M" + i, day.atTime(9, i)));
            legs.add(in(s, s + "M" + i, day.atTime(10, i)));
        }
        return legs;
    }

    @Test
    void 세_번째_중간_계좌가_T로_보내는_분에_경보_1건() {
        List<ScatterGatherAlert> alerts = rule.alerts(fan("S", 3, D), D);
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.minute()).isEqualTo(D.atTime(10, 2));
            assertThat(a.source()).isEqualTo("S");
            assertThat(a.paths()).hasSize(3);
            assertThat(a.valid(rule)).isTrue();
            assertThat(a.toRuleFacts().get("intermediates").asInt()).isEqualTo(3);
            assertThat(a.toRuleFacts().get("source_account_key").asText()).isEqualTo("S");
        });
    }

    @Test
    void 중간_계좌가_2개면_경보_없음() {
        assertThat(rule.alerts(fan("S", 2, D), D)).isEmpty();
    }

    @Test
    void 같은_분에_받고_보낸_중간_계좌는_세지_않음() {
        List<ScatterGatherLeg> legs = new ArrayList<>(fan("S", 2, D));
        legs.add(out("S", "SM2", D.atTime(10, 2)));
        legs.add(in("S", "SM2", D.atTime(10, 2)));
        assertThat(rule.alerts(legs, D)).isEmpty();
    }

    @Test
    void 참이_이어지면_경보_없음_1분_전_창에_이미_N_이상() {
        List<ScatterGatherLeg> legs = new ArrayList<>(fan("S", 3, D.minusDays(1)));   // 전날 완성 — 아직 창 안
        legs.addAll(fan("S2", 3, D));
        assertThat(rule.alerts(legs, D)).isEmpty();
    }

    @Test
    void 대표_경로는_가장_이른_S에서_M과_그보다_늦은_가장_이른_M에서_T_나머지_유효_다리는_근거에만() {
        List<ScatterGatherLeg> legs = new ArrayList<>(fan("S", 3, D));
        ScatterGatherLeg early = out("S", "SM0", D.atTime(8, 0));   // SM0 의 가장 이른 받은 거래 → 대표
        ScatterGatherLeg late = out("S", "SM0", D.atTime(11, 0));   // M→T 보다 늦음 → 유효 경로 아님
        legs.add(early);
        legs.add(late);
        ScatterGatherAlert a = rule.alerts(legs, D).get(0);
        assertThat(a.paths().get(0).get(0)).isEqualTo(early);
        assertThat(a.transactionIds()).contains(early.id(), 1L);   // 09:00 받은 거래(id 1)도 유효 경로라 근거에
        assertThat(a.transactionIds()).doesNotContain(late.id());
        assertThat(a.valid(rule)).isTrue();
    }

    @Test
    void 출발_계좌가_여럿이면_중간_계좌가_가장_많은_곳() {
        List<ScatterGatherLeg> legs = new ArrayList<>(fan("A", 3, D));
        for (int i = 0; i < 4; i++) {   // B 는 4곳, 모두 10:02 에 T 로
            legs.add(out("B", "BM" + i, D.atTime(9, i)));
            legs.add(in("B", "BM" + i, D.atTime(10, 2)));
        }
        assertThat(rule.alerts(legs, D)).singleElement()
                .satisfies(a -> assertThat(a.source()).isEqualTo("B"));
    }

    @Test
    void 중간_계좌_수가_같으면_계좌_키_순으로_앞_S() {   // #139 리뷰 — B 를 먼저 넣어도 A
        List<ScatterGatherLeg> legs = new ArrayList<>(fan("B", 3, D));
        legs.addAll(fan("A", 3, D));
        assertThat(rule.alerts(legs, D)).singleElement().satisfies(a -> {
            assertThat(a.source()).isEqualTo("A");
            assertThat(a.paths()).allSatisfy(p -> assertThat(p.get(0).source()).isEqualTo("A"));
        });
    }

    @Test
    void 출발과_도착이_같거나_경로가_끊기면_검사_실패() {
        ScatterGatherAlert ok = rule.alerts(fan("S", 3, D), D).get(0);
        ScatterGatherAlert sameEnds = new ScatterGatherAlert("S", ok.minute(), "S", ok.paths(), ok.transactionIds());
        List<List<ScatterGatherLeg>> broken = new ArrayList<>(ok.paths());
        broken.set(0, List.of(ok.paths().get(0).get(0), ok.paths().get(1).get(1)));   // M0 받고 M1 이 보냄
        assertThat(sameEnds.valid(rule)).isFalse();
        assertThat(new ScatterGatherAlert("T", ok.minute(), "S", broken, ok.transactionIds()).valid(rule)).isFalse();
    }

    @Test
    void 기준값은_params에서() throws Exception {
        assertThat(ScatterGatherRule.from(OM.readTree("{\"window_hours\": 96, \"min_intermediates\": 3}")))
                .isEqualTo(new ScatterGatherRule(96, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"window_hours\": 96}", "{\"min_intermediates\": 3}",
            "{\"window_hours\": 96, \"min_intermediates\": 0}", "{\"window_hours\": 96, \"min_intermediates\": \"3\"}"})
    void 기준값이_없거나_잘못되면_INVALID_RULE_PARAMS(String params) {
        assertThatThrownBy(() -> ScatterGatherRule.from(OM.readTree(params)))
                .isInstanceOf(InvalidRuleParamsException.class);
    }
}
