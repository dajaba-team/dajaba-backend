package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.stream.Stream;

/**
 * R06 경보 한 건의 근거 — 창 안의 입금(받은 쪽 금액)·출금(보낸 쪽 금액)을 통화별로, 자기 계좌 거래 제외 (rules.md R06).
 * 입금·출금 근거는 R02·R01 과 같은 쿼리(fanInEvidence·fanOutEvidence)로 만든다.
 */
public record GatherScatterEvidence(FanEvidence inbound, FanEvidence outbound) {

    public String entityId() {
        return inbound.entityId();
    }

    /** 최상위 transaction_ids — 입금·출금 근거의 합집합, 거래 id 순. */
    public List<Long> transactionIds() {
        return Stream.concat(inbound.transactionIds().stream(), outbound.transactionIds().stream())
                .distinct().sorted().toList();
    }

    /** {distinct_senders, distinct_receivers, inbound_by_currency:[…], outbound_by_currency:[…]} */
    public ObjectNode toRuleFacts(int distinctSenders, int distinctReceivers) {
        ObjectNode facts = JsonNodeFactory.instance.objectNode()
                .put("distinct_senders", distinctSenders)
                .put("distinct_receivers", distinctReceivers);
        facts.set("inbound_by_currency", inbound.amountsByCurrency());
        facts.set("outbound_by_currency", outbound.amountsByCurrency());
        return facts;
    }
}
