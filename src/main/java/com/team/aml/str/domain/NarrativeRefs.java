package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 검증 응답 narrative_refs(규약 §5.3) 를 본문에 덮어 읽는다 — 문장(text)은 그대로, 근거(cites·evidence_ids)만.
 * 저장하지 않는다(진희 11/10): 수정본은 사람이 고친 기록이라 AI 근거 갱신을 섞지 않고, 값은 이미 verification_results.payload 에 있다.
 * refs 가 null·배열이 아니면 본문 그대로. refs 에 없는 단락, 모르는 단락 이름, 모양이 틀린 항목은 그 단락만 수정본 값을 쓴다.
 */
public final class NarrativeRefs {

    private NarrativeRefs() {}

    public static List<NarrativeSection> apply(List<NarrativeSection> body, JsonNode narrativeRefs) {
        if (narrativeRefs == null || !narrativeRefs.isArray()) {
            return body;
        }
        Map<String, JsonNode> bySection = new HashMap<>();
        narrativeRefs.forEach(ref -> bySection.putIfAbsent(ref.path("section").asText(""), ref));
        return body.stream().map(s -> withRefs(s, bySection.get(s.section().name()))).toList();
    }

    private static NarrativeSection withRefs(NarrativeSection s, JsonNode ref) {
        if (ref == null || !ref.path("cites").isArray() || !ref.path("evidence_ids").isArray()) {
            return s;
        }
        List<String> cites = new ArrayList<>();
        ref.path("cites").forEach(c -> cites.add(c.asText()));
        List<Long> evidenceIds = new ArrayList<>();
        for (JsonNode id : ref.path("evidence_ids")) {
            if (!id.canConvertToLong()) {
                return s;   // 숫자가 아닌 근거 번호 — 이 단락은 반영하지 않는다
            }
            evidenceIds.add(id.asLong());
        }
        return new NarrativeSection(s.section(), s.text(), cites, evidenceIds);
    }
}
