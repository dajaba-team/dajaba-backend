package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * R02 경보 한 건의 근거 — 창 안의 입금(자기 계좌 거래 제외)을 통화별로 묶은 것 (ML 규약 §2.3 amounts_by_currency).
 * 금액은 받은 쪽(amount_received·currency_received). 통화는 원문 그대로, 다른 통화끼리 더하지 않는다.
 */
public record FanInEvidence(String entityId, List<CurrencyTotal> byCurrency) {

    public FanInEvidence {
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

    /** {"distinct_senders": n, "amounts_by_currency": [{currency, total_amount, transaction_count, transaction_ids}]} */
    public ObjectNode toRuleFacts(int distinctSenders) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode facts = f.objectNode().put("distinct_senders", distinctSenders);
        ArrayNode amounts = facts.putArray("amounts_by_currency");
        for (CurrencyTotal c : byCurrency) {
            ObjectNode item = amounts.addObject()
                    .put("currency", c.currency())
                    .put("total_amount", plain(c.totalAmount()))
                    .put("transaction_count", c.transactionCount());
            ArrayNode ids = item.putArray("transaction_ids");
            c.transactionIds().forEach(ids::add);
        }
        return facts;
    }

    /** numeric(20,6) 합계의 끝 0 을 떼되 지수 표기(1E+3)는 만들지 않는다. */
    private static BigDecimal plain(BigDecimal amount) {
        return new BigDecimal(amount.stripTrailingZeros().toPlainString());
    }
}
