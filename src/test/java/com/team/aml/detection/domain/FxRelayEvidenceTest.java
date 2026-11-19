package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R03 근거 — rule_facts 모양과 저장 전 검사 (rules.md 2.1). DB 없이. */
class FxRelayEvidenceTest {

    private static final FxRelayRule RULE = new FxRelayRule(96, new BigDecimal("0.1"), true,
            Map.of("US Dollar", BigDecimal.ONE, "Euro", new BigDecimal("0.8534")));
    private static final LocalDateTime START = LocalDateTime.of(2022, 8, 29, 11, 1);
    private static final LocalDateTime END = LocalDateTime.of(2022, 9, 2, 11, 1);

    private static RelayPair pair(long inId, LocalDateTime inTs, long outId, LocalDateTime outTs) {
        return new RelayPair(inId, inTs, "M", "US Dollar", new BigDecimal("100"),
                outId, outTs, "M", "Euro", new BigDecimal("85.34"));
    }

    private static FxRelayEvidence evidence(RelayPair... pairs) {
        return new FxRelayEvidence("E_M", "M", START, END, List.of(pairs));
    }

    @Test
    void rule_facts는_paths와_relays가_같은_순서() {
        FxRelayEvidence e = evidence(pair(10, END.minusHours(3), 12, END.minusMinutes(1)),
                pair(10, END.minusHours(3), 11, END.minusHours(2)));   // 같은 받은 거래가 두 쌍에 — 경로마다 그대로
        JsonNode facts = e.toRuleFacts(RULE);

        assertThat(facts.get("paths").toString()).isEqualTo("[[10,12],[10,11]]");
        assertThat(facts.get("relays")).hasSize(2);
        JsonNode r = facts.get("relays").get(0);
        assertThat(r.get("in_currency").asText()).isEqualTo("US Dollar");
        assertThat(r.get("in_amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(r.get("out_currency").asText()).isEqualTo("Euro");
        assertThat(r.get("out_amount").decimalValue()).isEqualByComparingTo("85.34");
        assertThat(r.get("ratio").decimalValue()).isEqualByComparingTo("1");
        assertThat(e.transactionIds()).containsExactly(10L, 11L, 12L);   // 한 번만
        assertThat(e.valid(RULE)).isTrue();
    }

    @Test
    void 같은_분이거나_창_밖이거나_다른_계좌면_검사_실패() {
        LocalDateTime t = END.minusMinutes(1);
        assertThat(evidence(pair(1, t, 2, t)).valid(RULE)).isFalse();                              // 같은 분
        assertThat(evidence(pair(1, START.minusMinutes(1), 2, t)).valid(RULE)).isFalse();          // 받은 거래가 창 밖
        assertThat(new FxRelayEvidence("E_M", "M", START, END, List.of(new RelayPair(1, START, "X", "US Dollar",
                new BigDecimal("100"), 2, t, "M", "Euro", new BigDecimal("85.34")))).valid(RULE)).isFalse();   // 다른 계좌가 받음
        assertThat(evidence().valid(RULE)).isFalse();                                               // 쌍 없음
    }
}
