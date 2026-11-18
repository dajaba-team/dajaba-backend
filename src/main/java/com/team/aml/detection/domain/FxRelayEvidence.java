package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * R03 경보 한 건의 근거 — 창 [windowStart, windowEnd) 안의 모든 중계 쌍, 받은 거래 시각순 (rules.md 2.1 paths 모양).
 * valid() 가 rules.md 2.1 저장 전 검사 — 쌍마다 받은 계좌 = 보낸 계좌 = 대상 계좌, 보낸 거래가 늦은 분, 둘 다 창 안, 통화·금액 조건.
 * paths 의 거래 합집합 = transaction_ids 는 만드는 방식으로 성립한다.
 */
public record FxRelayEvidence(String entityId, String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd,
                              List<RelayPair> pairs) {

    public FxRelayEvidence {
        pairs = List.copyOf(pairs);
    }

    /** 하나라도 어긋나면 false — 룰 스캔은 경보를 만들지 않는다. */
    public boolean valid(FxRelayRule rule) {
        return !pairs.isEmpty() && pairs.stream().allMatch(p ->
                accountKey.equals(p.inAccount()) && accountKey.equals(p.outAccount())
                        && p.inTs().truncatedTo(ChronoUnit.MINUTES).isBefore(p.outTs().truncatedTo(ChronoUnit.MINUTES))
                        && inWindow(p.inTs()) && inWindow(p.outTs())
                        && rule.relays(p));
    }

    private boolean inWindow(LocalDateTime ts) {
        return !ts.isBefore(windowStart) && ts.isBefore(windowEnd);
    }

    /** 최상위 transaction_ids — 모든 쌍의 거래 합집합, 거래 id 순. */
    public List<Long> transactionIds() {
        return pairs.stream().flatMap(p -> Stream.of(p.inId(), p.outId())).distinct().sorted().toList();
    }

    /** {paths:[[a,b],…], relays:[{in_currency, in_amount, out_currency, out_amount, ratio}]} — 같은 순서. 통화는 원문, 금액은 방향대로. */
    public ObjectNode toRuleFacts(FxRelayRule rule) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode facts = f.objectNode();
        ArrayNode paths = facts.putArray("paths");
        ArrayNode relays = facts.putArray("relays");
        for (RelayPair p : pairs) {
            paths.addArray().add(p.inId()).add(p.outId());
            relays.addObject()
                    .put("in_currency", p.inCurrency())
                    .put("in_amount", FanEvidence.plain(p.inAmount()))
                    .put("out_currency", p.outCurrency())
                    .put("out_amount", FanEvidence.plain(p.outAmount()))
                    .put("ratio", FanEvidence.plain(rule.ratio(p)));
        }
        return facts;
    }
}
