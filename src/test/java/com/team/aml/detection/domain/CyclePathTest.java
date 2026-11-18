package com.team.aml.detection.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** R04 저장 전 검사 (rules.md 2.1) + rule_facts 모양. DB 없이. */
class CyclePathTest {

    private static final CycleRule RULE = new CycleRule(96, 4);
    private static final LocalDateTime T = LocalDateTime.of(2022, 9, 5, 10, 0);
    private static final LocalDateTime START = RULE.windowStart(T.plusMinutes(2));
    private static final LocalDateTime END = RULE.windowEnd(T.plusMinutes(2));

    private static CyclePath path(List<String> from, List<String> to, List<LocalDateTime> ts) {
        return new CyclePath(List.of(1L, 2L, 3L).subList(0, ts.size()), ts, from, to);
    }

    @Test
    void 이어지고_분이_늘고_창_안인_3홉_고리는_통과() {
        CyclePath p = path(List.of("A", "B", "C"), List.of("B", "C", "A"), List.of(T, T.plusMinutes(1), T.plusMinutes(2)));
        assertThat(p.validFor(RULE, START, END)).isTrue();
        assertThat(new CycleAlert(T.plusMinutes(2), List.of(p)).toRuleFacts().toString())
                .isEqualTo("{\"paths\":[[1,2,3]],\"hops_by_path\":[3]}");
    }

    @Test
    void 같은_분_끊긴_고리_A로_안_돌아옴_중간_계좌_겹침_창_밖은_실패() {
        assertThat(path(List.of("A", "B"), List.of("B", "A"), List.of(T, T)).validFor(RULE, START, END)).isFalse();
        assertThat(path(List.of("A", "X"), List.of("B", "A"), List.of(T, T.plusMinutes(1))).validFor(RULE, START, END)).isFalse();
        assertThat(path(List.of("A", "B"), List.of("B", "C"), List.of(T, T.plusMinutes(1))).validFor(RULE, START, END)).isFalse();
        assertThat(path(List.of("A", "B", "A"), List.of("B", "A", "A"), List.of(T, T.plusMinutes(1), T.plusMinutes(2)))
                .validFor(RULE, START, END)).isFalse();
        assertThat(path(List.of("A", "B"), List.of("B", "A"), List.of(START.minusMinutes(1), T)).validFor(RULE, START, END)).isFalse();
    }

    @Test
    void max_hops보다_길면_실패() {
        CycleRule two = new CycleRule(96, 2);
        CyclePath p = path(List.of("A", "B", "C"), List.of("B", "C", "A"), List.of(T, T.plusMinutes(1), T.plusMinutes(2)));
        assertThat(p.validFor(two, START, END)).isFalse();
    }
}
