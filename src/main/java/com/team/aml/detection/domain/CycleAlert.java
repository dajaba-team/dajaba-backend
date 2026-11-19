package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.List;

/** R04 경보 한 건 — E 와 그 분에 닫힌 A 고리 전부(첫 거래 시각순). */
public record CycleAlert(LocalDateTime minute, List<CyclePath> paths) {

    public CycleAlert {
        paths = List.copyOf(paths);
    }

    public String accountKey() {
        return paths.get(0).accountKey();
    }

    /** 저장 전 검사 — 경로가 있고, 모두 같은 대상 계좌·같은 닫힌 분 E 이며, 경로마다 CyclePath.validFor. 어긋나면 경보를 만들지 않는다. */
    public boolean valid(CycleRule rule) {
        LocalDateTime start = rule.windowStart(minute);
        LocalDateTime end = rule.windowEnd(minute);
        return !paths.isEmpty() && paths.stream().allMatch(p -> p.accountKey().equals(accountKey())
                && p.closeMinute().equals(minute) && p.validFor(rule, start, end));
    }

    /** 최상위 transaction_ids — 모든 고리 거래의 합집합, 거래 id 순 (여러 고리가 같은 거래를 쓰면 한 번만). */
    public List<Long> transactionIds() {
        return paths.stream().flatMap(p -> p.ids().stream()).distinct().sorted().toList();
    }

    /** {paths:[[t1…tk],…], hops_by_path:[k,…]} — 같은 순서 (rules.md 2.1). */
    public ObjectNode toRuleFacts() {
        ObjectNode facts = JsonNodeFactory.instance.objectNode();
        ArrayNode pathsNode = facts.putArray("paths");
        ArrayNode hops = facts.putArray("hops_by_path");
        for (CyclePath p : paths) {
            ArrayNode path = pathsNode.addArray();
            p.ids().forEach(path::add);
            hops.add(p.hops());
        }
        return facts;
    }
}
