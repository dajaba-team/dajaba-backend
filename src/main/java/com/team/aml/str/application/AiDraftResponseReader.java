package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.str.domain.GeneratedDraft;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 초안 응답 JSON(규약 5.2) → GeneratedDraft. AiDtos 의 record 모양이 바뀌어도 응답 원문(raw)만 보면 되게 여기 한 곳에서 푼다.
 * 읽는 칸: draft_id, generation_id, generation_meta.prompt_version, produced_at, narrative[{section, text, cites, evidence_ids}].
 * 응답이 규약과 다르면 IllegalArgumentException — 메시지에 본문 글자는 넣지 않는다.
 */
final class AiDraftResponseReader {

    private AiDraftResponseReader() {}

    /**
     * @param raw          AI 응답 전체
     * @param sentDraftId  요청 기록에 남긴, AI 에 보낸 draft_id 글자(DRF-000312). 응답의 draft_id 가 이것과 달라야 할 이유가 없다
     */
    static GeneratedDraft read(JsonNode raw, String sentDraftId) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("AI 초안 응답이 JSON 객체가 아니다");
        }
        String draftId = raw.path("draft_id").asText(null);
        if (sentDraftId == null || !sentDraftId.equals(draftId)) {
            throw new IllegalArgumentException("AI 응답의 draft_id 가 보낸 것과 다르다 (보냄 " + sentDraftId + ", 받음 " + draftId + ")");
        }
        return new GeneratedDraft(
                raw.path("generation_id").asText(null),
                raw.path("generation_meta").path("prompt_version").asText(null),
                instant(raw.path("produced_at")),
                raw,
                narrative(raw.path("narrative")));
    }

    private static Instant instant(JsonNode node) {
        try {
            return Instant.parse(node.asText(""));   // UTC Z (규약 0절)
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("AI 응답의 produced_at 을 읽을 수 없다: " + node.asText(null), e);
        }
    }

    private static List<NarrativeSection> narrative(JsonNode array) {
        if (!array.isArray()) {
            throw new IllegalArgumentException("AI 응답에 narrative 배열이 없다");
        }
        List<NarrativeSection> sections = new ArrayList<>();
        for (JsonNode s : array) {
            sections.add(new NarrativeSection(
                    section(s.path("section").asText(null)),
                    s.path("text").asText(null),
                    strings(s.path("cites")),
                    longs(s.path("evidence_ids"))));
        }
        return sections;
    }

    private static StrSection section(String name) {
        try {
            return StrSection.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("모르는 단락 이름: " + name, e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));   // 칸이 없으면(MissingNode) 빈 목록
        return out;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (!n.canConvertToLong() || !n.isIntegralNumber()) {
                throw new IllegalArgumentException("evidence_ids 에 거래 번호가 아닌 값이 있다");
            }
            out.add(n.asLong());
        }
        return out;
    }
}
