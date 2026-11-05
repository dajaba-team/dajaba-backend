package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-151 — `score_request.json` 안의 `alerts[]` 모양이 `AlertPayload` 와 왕복.
 * 핵심: `created_at` 필드가 포함돼야 — `WebClientAiClient:79` 옛 TODO 닫음.
 * 점수 응답·요청의 다른 칸 확장은 SCRUM-128 범위라 이 테스트에서 안 봄.
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
}
