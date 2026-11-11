package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** narrative_refs 덮어 읽기 — 스프링 없이. 문장은 그대로, 근거만, 받은 단락만. */
class NarrativeRefsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<NarrativeSection> BODY = List.of(
            new NarrativeSection(StrSection.SUBJECT, "거래자 문장", List.of("subject.entity_name"), List.of(1L)),
            new NarrativeSection(StrSection.DATE, "일자 문장", List.of("period"), List.of(1L, 2L)));

    private static JsonNode refs(String json) throws Exception {
        return JSON.readTree(json);
    }

    // 1
    @Test
    void 정상_받은_단락은_cites와_evidence_ids만_바뀌고_문장과_나머지_단락은_그대로() throws Exception {
        List<NarrativeSection> out = NarrativeRefs.apply(BODY, refs(
                "[{\"section\":\"DATE\",\"cites\":[\"period\",\"inbound.count\"],\"evidence_ids\":[2,3]}]"));

        assertThat(out.get(0)).isEqualTo(BODY.get(0));
        assertThat(out.get(1).text()).isEqualTo("일자 문장");
        assertThat(out.get(1).cites()).containsExactly("period", "inbound.count");
        assertThat(out.get(1).evidenceIds()).containsExactly(2L, 3L);
    }

    // 2
    @Test
    void 정상_null이거나_배열이_아니면_본문_그대로이고_모양이_틀린_항목은_그_단락만_건너뛴다() throws Exception {
        assertThat(NarrativeRefs.apply(BODY, null)).isSameAs(BODY);
        assertThat(NarrativeRefs.apply(BODY, JSON.nullNode())).isSameAs(BODY);
        assertThat(NarrativeRefs.apply(BODY, refs("{}"))).isSameAs(BODY);

        List<NarrativeSection> out = NarrativeRefs.apply(BODY, refs("[{\"section\":\"SUBJECT\",\"cites\":[\"x\"],"
                + "\"evidence_ids\":[\"abc\"]},{\"section\":\"HELLO\",\"cites\":[],\"evidence_ids\":[]}]"));
        assertThat(out).isEqualTo(BODY);
    }
}
