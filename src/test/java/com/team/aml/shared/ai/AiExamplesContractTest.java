package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-151 — `contracts/examples/` 예시 JSON 이 `AiDtos` 와 왕복 (모르는 칸·빠진 칸 0).
 * AI 리포와 같은 파일(바이트 그대로), 바꿀 때 양쪽 복사. `contracts/examples/README.md` 참조.
 */
class AiExamplesContractTest {

    private static final Path EXAMPLES = Path.of("contracts/examples");
    private final ObjectMapper om = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private JsonNode readExample(String name) throws Exception {
        return om.readTree(Files.readString(EXAMPLES.resolve(name)));
    }

    /** `WebClientAiClient.injectRaw` 와 같은 모양 — 응답 본문에 `raw` 로 원문 전체를 복사해 넣는다. */
    private JsonNode withRaw(JsonNode original) {
        ObjectNode copy = (ObjectNode) original.deepCopy();
        copy.set("raw", original);
        return copy;
    }

    @Test
    void draft_request_roundtrip() throws Exception {
        JsonNode original = readExample("draft_request.json");
        StrDraftRequest dto = om.treeToValue(original, StrDraftRequest.class);
        JsonNode roundtripped = om.valueToTree(dto);
        assertThat(roundtripped.toString()).isEqualTo(original.toString());
        // 핵심 칸 값 확인
        assertThat(dto.draftId()).isEqualTo("DRF-000312");
        assertThat(dto.caseNo()).isEqualTo("CASE-2022-000057");
        assertThat(dto.alert().alertId()).isEqualTo("ALT-20220903-000142");
        assertThat(dto.alert().createdAt()).isEqualTo("2026-11-03T01:00:00Z");
        assertThat(dto.evidenceTransactionIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(dto.priorAlertCount()).isEqualTo(1);
        assertThat(dto.reportingInstitution()).isEqualTo("Portugal Bank #4507");
    }

    @Test
    void draft_response_fields_populated_and_raw_has_original() throws Exception {
        JsonNode original = readExample("draft_response.json");
        StrDraftResponse dto = om.treeToValue(withRaw(original), StrDraftResponse.class);
        assertThat(dto.draftId()).isEqualTo("DRF-000312");
        assertThat(dto.generationId()).isEqualTo("g-7c1d44e2");
        assertThat(dto.narrative()).hasSize(6);  // 예시는 v1 6섹션
        assertThat(dto.narrative().get(0).section()).isEqualTo("SUMMARY");
        assertThat(dto.narrative().get(0).cites()).isNotEmpty();
        assertThat(dto.promptVersion()).isEqualTo("narrative_v1");
        assertThat(dto.raw().toString()).isEqualTo(original.toString());  // 원문 전체 보존
    }

    @Test
    void verify_request_roundtrip_has_no_draft_id_field() throws Exception {
        JsonNode original = readExample("verify_request.json");
        assertThat(original.has("draft_id")).isFalse();  // AI StrictModel 이라 안 들어감
        VerifyRequest dto = om.treeToValue(original, VerifyRequest.class);
        JsonNode roundtripped = om.valueToTree(dto);
        assertThat(roundtripped.toString()).isEqualTo(original.toString());
        assertThat(dto.generationId()).isEqualTo("g-7c1d44e2");
        assertThat(dto.revisionNo()).isEqualTo(2);
        assertThat(dto.narrative()).hasSize(6);
    }

    @Test
    void verify_response_code_failed_fields_populated() throws Exception {
        JsonNode original = readExample("verify_response.json");
        VerifyResponse dto = om.treeToValue(withRaw(original), VerifyResponse.class);
        assertThat(dto.verificationId()).isEqualTo("v-9c1d44");
        assertThat(dto.generationId()).isEqualTo("g-7c1d44e2");
        assertThat(dto.revisionNo()).isEqualTo(2);
        assertThat(dto.narrativeSha256()).isNotNull();
        assertThat(dto.passed()).isFalse();
        assertThat(dto.status()).isEqualTo("CODE_FAILED");
        assertThat(dto.verifierVersion()).isEqualTo("verify-2026.11.1+judge_v2");
        assertThat(dto.raw().toString()).isEqualTo(original.toString());
    }

    @Test
    void verify_response_passed_fields_populated() throws Exception {
        JsonNode original = readExample("verify_response_passed.json");
        VerifyResponse dto = om.treeToValue(withRaw(original), VerifyResponse.class);
        assertThat(dto.passed()).isTrue();
        assertThat(dto.status()).isEqualTo("PASSED");
        assertThat(dto.verifierVersion()).isEqualTo("verify-2026.11.1+judge_v2");
        assertThat(dto.raw().toString()).isEqualTo(original.toString());
    }
}
