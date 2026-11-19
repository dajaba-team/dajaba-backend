package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * R01·R02 경보 한 건의 근거 — 창 안의 송금(R01)·입금(R02)을 통화별로 묶은 것, 자기 계좌 거래 제외 (ML 규약 §2.3).
 * 금액은 방향대로(rules.md 1절 4) — R01 은 보낸 쪽(amount_paid·currency_paid), R02 는 받은 쪽(amount_received·currency_received).
 * 통화는 원문 그대로, 다른 통화끼리 더하지 않는다.
 */
public record FanEvidence(String entityId, List<CurrencyTotal> byCurrency) {

    public FanEvidence {
        byCurrency = List.copyOf(byCurrency);
    }

    public record CurrencyTotal(String currency, BigDecimal totalAmount, int transactionCount,
                                List<Long> transactionIds) {
        public CurrencyTotal {
            transactionIds = List.copyOf(transactionIds);
        }
    }

    /** 최상위 transaction_ids — 통화별 근거 ID의 합집합, 거래 id 순. */
    public List<Long> transactionIds() {
        return byCurrency.stream().flatMap(c -> c.transactionIds().stream()).distinct().sorted().toList();
    }

    /**
     * {countField: n, "amounts_by_currency": [{currency, total_amount, transaction_count, transaction_ids}]}
     * — countField 는 R01 distinct_receivers, R02 distinct_senders.
     */
    public ObjectNode toRuleFacts(String countField, int count) {
        ObjectNode facts = JsonNodeFactory.instance.objectNode().put(countField, count);
        facts.set("amounts_by_currency", amountsByCurrency());
        return facts;
    }

    /** [{currency, total_amount, transaction_count, transaction_ids}] — R01·R02 amounts_by_currency, R06 inbound·outbound_by_currency. */
    public ArrayNode amountsByCurrency() {
        ArrayNode amounts = JsonNodeFactory.instance.arrayNode();
        for (CurrencyTotal c : byCurrency) {
            ObjectNode item = amounts.addObject()
                    .put("currency", c.currency())
                    .put("total_amount", plain(c.totalAmount()))
                    .put("transaction_count", c.transactionCount());
            ArrayNode ids = item.putArray("transaction_ids");
            c.transactionIds().forEach(ids::add);
        }
        return amounts;
    }

    /** numeric(20,6) 합계의 끝 0 을 떼되 지수 표기(1E+3)는 만들지 않는다. */
    static BigDecimal plain(BigDecimal amount) {
        return new BigDecimal(amount.stripTrailingZeros().toPlainString());
    }
}
