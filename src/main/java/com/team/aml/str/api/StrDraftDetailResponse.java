package com.team.aml.str.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.application.GetStrDraft;
import com.team.aml.str.domain.NarrativeSection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S2 응답. 키는 META_ONLY 여도 모두 나간다(값만 null) — @JsonInclude 를 쓰지 않는다.
 * facts 의 *account_key 는 원문 대신 *account_no(가린 값)·*account_ref 로 바꾼다 (계약서 2.4, 미결 19).
 */
public record StrDraftDetailResponse(
        String draftNo,
        String access,
        String caseNo,
        String status,
        Long version,
        Long caseVersion,
        Instant producedAt,
        JsonNode facts,
        List<Narrative> narrative,
        Integer currentRevisionNo,
        JsonNode latestVerification
) {

    private static final String ACCOUNT_KEY = "account_key";

    public static StrDraftDetailResponse from(GetStrDraft.Result r) {
        return new StrDraftDetailResponse(
                r.draftNo(),
                r.access().name(),
                r.caseNo(),
                r.status().name(),
                r.version(),
                r.caseVersion(),
                r.producedAt(),
                r.facts() == null ? null : maskAccountKeys(r.facts()),
                r.narrative() == null ? null : r.narrative().stream().map(Narrative::from).toList(),
                r.currentRevisionNo(),
                r.latestVerification());
    }

    /** 원본은 두고 복사본을 고친다. */
    private static JsonNode maskAccountKeys(JsonNode facts) {
        JsonNode copy = facts.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (name.endsWith(ACCOUNT_KEY)) {
                    String prefix = name.substring(0, name.length() - ACCOUNT_KEY.length());
                    String key = value == null || value.isNull() ? null : value.asText();
                    object.remove(name);
                    object.put(prefix + "account_no", StrAccountDisplay.maskedNo(key));
                    object.put(prefix + "account_ref", StrAccountDisplay.ref(key));
                } else {
                    mask(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrDraftDetailResponse::mask);
        }
    }

    public record Narrative(String section, String text, List<String> cites, List<Long> evidenceIds) {

        static Narrative from(NarrativeSection s) {
            return new Narrative(s.section().name(), s.text(), s.cites(), s.evidenceIds());
        }
    }
}
