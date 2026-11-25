package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.ReferenceResult;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchRequest;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-426 — AI 검색 결과 `excerpt` 를 `ReferenceResult` 로 읽기. AI 계약 예시엔 아직 칸이 없어(AI SCRUM-425 머지 전)
 * 예시에 칸을 더한 픽스처로 본다. AI SCRUM-425 머지 뒤 계약 예시로 한 번 더 확인.
 */
class ReferenceExcerptDtoTest {

    private static final ObjectMapper OM = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static JsonNode example(String name) throws Exception {
        return OM.readTree(Files.readString(Path.of("contracts/examples").resolve(name)));
    }

    private static ReferenceSearchResponse read(JsonNode body) throws Exception {
        ObjectNode copy = (ObjectNode) body.deepCopy();
        copy.set("raw", body);
        return OM.treeToValue(copy, ReferenceSearchResponse.class);
    }

    @Test
    void 정상_snake_case_excerpt를_읽고_없거나_null이면_null() throws Exception {
        ObjectNode body = (ObjectNode) example("reference_search_response_found.json");
        ((ObjectNode) body.get("results").get(0)).put("excerpt", "Funds were moved through a cycle of accounts.");
        ((ObjectNode) body.get("results").get(1)).putNull("excerpt");
        ReferenceSearchRequest sent = OM.treeToValue(example("reference_search_request.json"),
                ReferenceSearchRequest.class);
        WebClientAiClient.requireReferenceSearchResponse(sent, body);

        ReferenceSearchResponse r = read(body);

        assertThat(r.results().get(0).excerpt()).isEqualTo("Funds were moved through a cycle of accounts.");
        assertThat(r.results().get(1).excerpt()).isNull();
        assertThat(read(example("reference_search_response_found.json")).results())
                .extracting(ReferenceResult::excerpt).containsOnlyNulls();   // 지금 계약 예시(칸 없음)
    }

    @Test
    void 경계_excerpt가_null이면_record를_JSON으로_쓸_때_칸을_안_만든다() throws Exception {   // 계약 예시 왕복(9칸) 유지
        JsonNode original = example("reference_search_response_found.json").get("results").get(0);
        ReferenceResult r = read(example("reference_search_response_found.json")).results().get(0);

        JsonNode back = OM.valueToTree(r);

        assertThat(back.has("excerpt")).isFalse();
        assertThat(back).isEqualTo(original);
    }
}
