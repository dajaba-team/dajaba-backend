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
}
