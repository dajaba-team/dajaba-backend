package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.domain.GeneratedDraft;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 초안 응답(규약 5.2, v0.4 7항목) → GeneratedDraft. 스프링 없이 돈다. */
class AiDraftResponseReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SENT = "DRF-000312";

    /** 규약 5.2 모양의 7항목 응답. 단락마다 cites 2개, 근거 거래 3개. */
    static ObjectNode response(String draftId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", draftId)
                .put("generation_id", "g-7c1d44e2");
        r.putObject("facts").put("excluded_self_transfer_count", 0).putObject("inbound").put("count", 3);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2").put("llm_model", "test");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(104221).add(104233).add(104301);
        }
        r.putNull("verification");
        return r;
    }

    // 1
    @Test
    void 응답에서_생성_번호_버전_시각_단락을_읽는다() {
        GeneratedDraft g = AiDraftResponseReader.read(response(SENT), SENT);

        assertThat(g.generationId()).isEqualTo("g-7c1d44e2");
        assertThat(g.generatorVersion()).isEqualTo("narrative_v2");     // generation_meta.prompt_version (11/3)
        assertThat(g.producedAt()).isEqualTo(Instant.parse("2026-11-03T01:10:00Z"));
        assertThat(g.narrative()).extracting(NarrativeSection::section).containsExactlyElementsOf(StrSection.V2);
        NarrativeSection first = g.narrative().get(0);
        assertThat(first.text()).isEqualTo("AI SUBJECT 문장");
        assertThat(first.cites()).containsExactly("period", "inbound.count");
        assertThat(first.evidenceIds()).containsExactly(104221L, 104233L, 104301L);
    }

    // 2
    @Test
    void payload는_응답_전체다() {
        JsonNode raw = response(SENT);

        GeneratedDraft g = AiDraftResponseReader.read(raw, SENT);

        assertThat(g.payload()).isEqualTo(raw);
        assertThat(g.payload().path("facts").isObject()).isTrue();
    }

    // 3: cites·evidence_ids 칸이 없으면 빈 목록 (예전 6섹션 목업 응답)
    @Test
    void cites나_evidence_ids_칸이_없으면_빈_목록이다() {
        ObjectNode raw = response(SENT);
        raw.withArray("narrative").forEach(n -> ((ObjectNode) n).remove(java.util.List.of("cites", "evidence_ids")));

        GeneratedDraft g = AiDraftResponseReader.read(raw, SENT);

        assertThat(g.narrative()).allSatisfy(n -> {
            assertThat(n.cites()).isEmpty();
            assertThat(n.evidenceIds()).isEmpty();
        });
    }

    // 4
    @Test
    void 응답의_draft_id가_보낸_것과_다르면_거부한다() {
        assertThatThrownBy(() -> AiDraftResponseReader.read(response("DRF-000313"), SENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft_id");
        assertThatThrownBy(() -> AiDraftResponseReader.read(response(SENT), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 5
    @Test
    void 모르는_단락_이름이면_거부하고_본문_글자는_메시지에_없다() {
        ObjectNode raw = response(SENT);
        ((ObjectNode) raw.withArray("narrative").get(0)).put("section", "NOT_A_SECTION").put("text", "비밀 문장");

        assertThatThrownBy(() -> AiDraftResponseReader.read(raw, SENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_SECTION")
                .hasMessageNotContaining("비밀 문장");
    }

    // 6
    @Test
    void produced_at이_없거나_모양이_틀리면_거부한다() {
        ObjectNode missing = response(SENT);
        missing.remove("produced_at");
        ObjectNode local = response(SENT).put("produced_at", "2026-11-03T01:10:00");   // Z 없음

        assertThatThrownBy(() -> AiDraftResponseReader.read(missing, SENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiDraftResponseReader.read(local, SENT)).isInstanceOf(IllegalArgumentException.class);
    }

    // 7
    @Test
    void narrative가_배열이_아니거나_근거_번호가_숫자가_아니면_거부한다() {
        ObjectNode noNarrative = response(SENT);
        noNarrative.remove("narrative");
        ObjectNode badEvidence = response(SENT);
        ((ObjectNode) badEvidence.withArray("narrative").get(0)).putArray("evidence_ids").add("104221");

        assertThatThrownBy(() -> AiDraftResponseReader.read(noNarrative, SENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiDraftResponseReader.read(badEvidence, SENT)).isInstanceOf(IllegalArgumentException.class);
    }
}
