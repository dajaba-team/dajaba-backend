package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.domain.GeneratedDraft;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import com.team.aml.shared.ai.AiClientException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 초안 응답(규약 5.2, v0.4 7항목) → GeneratedDraft. 스프링 없이 돈다.
 * 규약과 다른 응답은 AiClientException(AI_BAD_RESPONSE, 502) (#60 리뷰 1).
 */
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
        assertBadResponse(() -> AiDraftResponseReader.read(response("DRF-000313"), SENT))
                .hasMessageContaining("draft_id");
        assertThatThrownBy(() -> AiDraftResponseReader.read(response(SENT), null))   // 요청 기록 쪽 문제 — 502 아님
                .isInstanceOf(IllegalStateException.class);
    }

    // 5
    @Test
    void 모르는_단락_이름이면_거부하고_본문_글자는_메시지에_없다() {
        ObjectNode raw = response(SENT);
        ((ObjectNode) raw.withArray("narrative").get(0)).put("section", "NOT_A_SECTION").put("text", "비밀 문장");

        assertBadResponse(() -> AiDraftResponseReader.read(raw, SENT))
                .hasMessageContaining("NOT_A_SECTION")
                .hasMessageNotContaining("비밀 문장");
    }

    // 6
    @Test
    void produced_at이_없거나_모양이_틀리면_거부한다() {
        ObjectNode missing = response(SENT);
        missing.remove("produced_at");
        ObjectNode local = response(SENT).put("produced_at", "2026-11-03T01:10:00");   // Z 없음

        assertBadResponse(() -> AiDraftResponseReader.read(missing, SENT));
        assertBadResponse(() -> AiDraftResponseReader.read(local, SENT));
    }

    // 6-2: Instant.parse 는 +09:00 도 받아 UTC 로 바꾼다 — 규약은 Z 지만 거부하지는 않는다 (#60 리뷰 3)
    @Test
    void produced_at이_오프셋_시각이면_UTC로_바꿔_읽는다() {
        ObjectNode kst = response(SENT).put("produced_at", "2026-11-03T10:10:00+09:00");

        assertThat(AiDraftResponseReader.read(kst, SENT).producedAt()).isEqualTo(Instant.parse("2026-11-03T01:10:00Z"));
    }

    // 7
    @Test
    void narrative가_배열이_아니거나_근거_번호가_숫자가_아니면_거부한다() {
        ObjectNode noNarrative = response(SENT);
        noNarrative.remove("narrative");
        ObjectNode badEvidence = response(SENT);
        ((ObjectNode) badEvidence.withArray("narrative").get(0)).putArray("evidence_ids").add("104221");

        assertBadResponse(() -> AiDraftResponseReader.read(noNarrative, SENT));
        assertBadResponse(() -> AiDraftResponseReader.read(badEvidence, SENT));
    }

    // 8: AI 리포와 같은 예시 파일(contracts/examples/draft_response.json, 옛 6섹션 narrative_v1)도 읽힌다 (#60 리뷰 2)
    @Test
    void 계약_예시_draft_response를_읽으면_옛_6섹션과_cites가_나온다() throws Exception {
        JsonNode example = JSON.readTree(Files.readString(Path.of("contracts/examples/draft_response.json")));

        GeneratedDraft g = AiDraftResponseReader.read(example, "DRF-000312");

        assertThat(g.generationId()).isEqualTo("g-7c1d44e2");
        assertThat(g.generatorVersion()).isEqualTo("narrative_v1");
        assertThat(g.producedAt()).isEqualTo(Instant.parse("2026-11-03T01:10:00Z"));
        assertThat(g.narrative()).extracting(NarrativeSection::section).containsExactlyElementsOf(StrSection.V1);
        assertThat(g.narrative().get(0).cites())
                .containsExactly("period", "inbound.count", "inbound.amount.USD", "counterparties.count");
        assertThat(g.narrative().get(0).evidenceIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(g.narrative().get(5).cites()).isEmpty();          // CONCLUSION 은 cites 가 빈 배열
        assertThat(g.payload()).isEqualTo(example);
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertBadResponse(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return assertThatThrownBy(call)
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
    }
}

