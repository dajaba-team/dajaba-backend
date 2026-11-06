package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.ai.AiDtos.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-151 — `MockAiClient` 가 새 DTO 모양으로 응답.
 * 핵심: 받은 draftId 그대로 반환, 7항목(v2) 섹션, 섹션마다 cites 1개 이상, promptVersion="narrative_v2".
 */
class MockAiClientTest {

    private final ObjectMapper om = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final MockAiClient client = new MockAiClient(om);

    private AlertPayload alert() {
        return new AlertPayload("ALT-X", "010_A", "ENT-1", "R02_FAN_IN_24H", "1", "FAN_IN",
                "2022-09-02T00:00:00", "2022-09-02T23:59:59", "2022-09-02T23:59:59",
                List.of(1L, 2L, 3L), null, "2026-11-03T01:00:00Z");
    }

    private StrDraftRequest req(String draftId) {
        return new StrDraftRequest(draftId, "CASE-1", alert(), List.of(1L, 2L, 3L),
                null, 0, "메모", "Bank");
    }

    @Test
    void createDraft_returns_request_draftId() {
        StrDraftResponse res = client.createDraft(req("DRF-000999"));
        assertThat(res.draftId()).isEqualTo("DRF-000999");  // 요청 draftId 그대로
    }

    @Test
    void createDraft_returns_v2_seven_sections_with_cites() {
        StrDraftResponse res = client.createDraft(req("DRF-000999"));
        assertThat(res.narrative()).hasSize(7);
        assertThat(res.narrative()).extracting(NarrativeSectionDto::section)
                .containsExactly("SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION");
        for (NarrativeSectionDto sec : res.narrative()) {
            assertThat(sec.cites()).as("섹션 %s cites".formatted(sec.section())).isNotEmpty();
            assertThat(sec.text()).isNotBlank();
        }
    }

    @Test
    void createDraft_prompt_version_is_narrative_v2() {
        StrDraftResponse res = client.createDraft(req("DRF-000999"));
        assertThat(res.promptVersion()).isEqualTo("narrative_v2");
    }

    @Test
    void createDraft_raw_has_generation_meta() {
        StrDraftResponse res = client.createDraft(req("DRF-000999"));
        JsonNode raw = res.raw();
        assertThat(raw.path("generation_meta").path("prompt_version").asText()).isEqualTo("narrative_v2");
        assertThat(raw.path("draft_id").asText()).isEqualTo("DRF-000999");
    }

    @Test
    void verify_returns_dto_with_passed_true_and_status() {
        VerifyResponse res = client.verify("DRF-000999",
                new VerifyRequest("g-1", 1, List.of(new NarrativeSectionDto("SUBJECT", "x", List.of(), List.of()))));
        assertThat(res.passed()).isTrue();
        assertThat(res.status()).isEqualTo("PASSED");
        assertThat(res.raw()).isNotNull();
    }

    // --- SCRUM-128 점수 — 규약 §3 모양 ---------------------------------------

    @Test
    void scores_returns_contract_shape_with_threshold_and_meta() {
        ScoreResult r = client.scores(7L, List.of(alert())).scores().get(0);

        assertThat(r.alertId()).isEqualTo("ALT-X");
        assertThat(r.dataQualityStatus()).isEqualTo("OK");
        assertThat(r.failureCode()).isNull();                     // OK 면 failure_code 없음
        assertThat(r.predictionId()).isNotBlank();                // OK 면 prediction_id·score 있음
        assertThat(r.score()).isBetween(0.05, 0.94);
        assertThat(r.threshold()).isBetween(0.0, 1.0);
        assertThat(r.meta().modelVersion()).isNotBlank();
        assertThat(r.meta().featureVersion()).isNotBlank();
        assertThat(r.meta().serverVersion()).isNotBlank();
        assertThat(r.meta().latencyMs()).isNotNegative();
        assertThat(r.producedAt()).endsWith("Z");
        assertThat(r.evidenceTransactionIds()).isSubsetOf(alert().transactionIds());
        assertThat(r.contextTransactionIds()).isEmpty();
        assertThat(r.reasons().isArray()).isTrue();

        JsonNode raw = r.raw();   // 항목 원문 — AI 항목에 없는 칸은 넣지 않음
        assertThat(raw.has("failure_code")).isTrue();
        assertThat(raw.has("threshold")).isTrue();
        assertThat(raw.has("score_band")).isFalse();
        assertThat(raw.has("model_version")).isFalse();
        assertThat(raw.has("produced_at")).isFalse();
    }

    @Test
    void health_returns_ai_shape_with_versions() {   // SCRUM-22 — AI /v1/health 칸
        HealthResponse h = client.health();
        assertThat(h.status()).isEqualTo("ok");
        assertThat(h.db()).isEqualTo("ok");
        assertThat(h.llm()).isEqualTo("ok");
        assertThat(h.serverVersion()).isNotBlank();
        assertThat(h.modelVersion()).isNotBlank();
        assertThat(h.featureVersion()).isNotBlank();
        assertThat(h.retrievalVersion()).isNotBlank();
    }

    @Test
    void scores_same_alert_same_score_by_hash_rule() {
        double expected = 0.05 + (Math.abs("ALT-X".hashCode()) % 90) / 100.0;
        assertThat(client.scores(1L, List.of(alert())).scores().get(0).score()).isEqualTo(expected);
        assertThat(client.scores(2L, List.of(alert())).scores().get(0).score()).isEqualTo(expected);
    }
}
