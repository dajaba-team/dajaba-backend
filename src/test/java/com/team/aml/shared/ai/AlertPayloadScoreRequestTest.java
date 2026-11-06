package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import com.team.aml.shared.ai.AiDtos.ScoresRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-151 — `score_request.json` 안의 `alerts[]` 모양이 `AlertPayload` 와 왕복.
 * 핵심: `created_at` 필드가 포함돼야 — `WebClientAiClient:79` 옛 TODO 닫음.
 * SCRUM-128 — 요청 전체(`ScoresRequest`) 왕복도 여기서. 응답은 `AiExamplesContractTest`.
 */
class AlertPayloadScoreRequestTest {

    private final ObjectMapper om = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void alert_payload_roundtrip_with_created_at() throws Exception {
        JsonNode root = om.readTree(Files.readString(Path.of("contracts/examples/score_request.json")));
        JsonNode original = root.path("alerts").get(0);
        AlertPayload dto = om.treeToValue(original, AlertPayload.class);

        JsonNode roundtripped = om.valueToTree(dto);
        assertThat(roundtripped.toString()).isEqualTo(original.toString());

        assertThat(dto.alertId()).isEqualTo("ALT-20220903-000142");
        assertThat(dto.createdAt()).isEqualTo("2026-11-03T01:00:00Z");
        assertThat(dto.transactionIds()).containsExactly(104221L, 104233L, 104301L);
    }

    /** SCRUM-128 — 요청 전체 `{batch_run_id, alerts}` 가 `ScoresRequest` 와 왕복. */
    @Test
    void scores_request_roundtrip_whole_file() throws Exception {
        JsonNode root = om.readTree(Files.readString(Path.of("contracts/examples/score_request.json")));
        ScoresRequest dto = om.treeToValue(root, ScoresRequest.class);

        assertThat(om.valueToTree(dto).toString()).isEqualTo(root.toString());
        assertThat(dto.batchRunId()).isEqualTo(7L);
        assertThat(dto.alerts()).hasSize(root.path("alerts").size());
    }
}
