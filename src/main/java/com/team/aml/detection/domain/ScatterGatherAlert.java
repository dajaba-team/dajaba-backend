package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R05 경보 한 건 — 도착 T·완성 분 E·출발 S, Mᵢ 마다 대표 경로 [S→Mᵢ, Mᵢ→T], 모든 유효 경로의 거래 (rules.md R05·2.1).
 * paths 합집합 ⊆ transaction_ids (대표만 담으므로 같지 않을 수 있다).
 */
public record ScatterGatherAlert(String target, LocalDateTime minute, String source,
                                 List<List<ScatterGatherLeg>> paths, List<Long> transactionIds) {

    public ScatterGatherAlert {
        paths = paths.stream().map(List::copyOf).toList();
        transactionIds = List.copyOf(transactionIds);
    }

    /** rules.md 2.1 저장 전 검사 (R05). 어긋나면 경보를 만들지 않는다. */
    public boolean valid(ScatterGatherRule rule) {
        if (source.equals(target) || paths.size() < rule.minIntermediates()) {
            return false;
        }
        LocalDateTime start = rule.windowStart(minute);
        LocalDateTime end = rule.windowEnd(minute);
        Set<String> mids = new HashSet<>();
        for (List<ScatterGatherLeg> p : paths) {
            if (p.size() != 2) {
                return false;
            }
            ScatterGatherLeg a = p.get(0);
            ScatterGatherLeg b = p.get(1);
            String m = a.to();
            boolean ok = a.from().equals(source) && b.to().equals(target) && b.from().equals(m)
                    && !m.equals(source) && !m.equals(target) && mids.add(m)
                    && a.minute().isBefore(b.minute())
                    && !a.ts().isBefore(start) && b.ts().isBefore(end)
                    && transactionIds.contains(a.id()) && transactionIds.contains(b.id());
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** {source_account_key, intermediates, paths:[[S→Mᵢ, Mᵢ→T],…]} — intermediates = 중간 계좌 수(= paths 수). */
    public ObjectNode toRuleFacts() {
        ObjectNode facts = JsonNodeFactory.instance.objectNode()
                .put("source_account_key", source)
                .put("intermediates", paths.size());
        ArrayNode arr = facts.putArray("paths");
        for (List<ScatterGatherLeg> p : paths) {
            arr.addArray().add(p.get(0).id()).add(p.get(1).id());
        }
        return facts;
    }
}
