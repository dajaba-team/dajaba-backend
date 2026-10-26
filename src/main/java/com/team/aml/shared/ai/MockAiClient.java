package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 서버 없이 개발할 때 쓰는 고정 응답 (aml.ai.mock=true — local/test 프로파일 기본).
 * 점수는 alert_id 해시로 0.05~0.95 사이를 결정적으로 돌려준다 → 같은 경보는 항상 같은 점수 (테스트에서 기대값 고정 가능).
 * 초안·검증·요약은 규약 5절 모양의 최소 JSON. 진짜 서버를 붙이려면 aml.ai.mock=false + AI_BASE_URL.
 */
@Component
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "true")
public class MockAiClient implements AiClient {

    private final ObjectMapper om;

    public MockAiClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ScoresResponse scores(List<AlertPayload> alerts) {
        List<ScoreResult> out = new ArrayList<>();
        for (AlertPayload a : alerts) {
            double score = 0.05 + (Math.abs(a.alertId().hashCode()) % 90) / 100.0;   // 0.05 ~ 0.94
            String band = score >= 0.7 ? "HIGH" : score >= 0.4 ? "MEDIUM" : "LOW";
            ObjectNode raw = om.createObjectNode()
                    .put("alert_id", a.alertId()).put("score", score).put("score_band", band)
                    .put("data_quality_status", "OK").put("model_version", "mock-0.0.1")
                    .put("prediction_id", "PRD-mock-" + Math.abs(a.alertId().hashCode()))
                    .put("produced_at", Instant.now().toString());
            raw.putArray("evidence_transaction_ids");
            raw.putArray("reasons");
            out.add(new ScoreResult(a.alertId(), score, band, "OK", "mock-0.0.1",
                    raw.get("prediction_id").asText(), raw.get("produced_at").asText(), raw));
        }
        return new ScoresResponse(out);
    }

    @Override
    public StrDraftResponse createDraft(StrDraftRequest request) {
        String draftId = "DRF-mock-" + UUID.randomUUID().toString().substring(0, 6);
        ObjectNode facts = om.createObjectNode().put("subject_entity_id", request.caseId()).put("transaction_count", 0);
        var narrative = om.createArrayNode();
        for (String s : List.of("SUMMARY", "WHO", "WHAT_WHEN_WHERE", "HOW", "WHY_SUSPICIOUS", "CONCLUSION")) {
            ObjectNode sec = om.createObjectNode().put("section", s).put("text", "(목업) " + s + " 섹션 문장");
            sec.putArray("evidence_ids");
            narrative.add(sec);
        }
        ObjectNode raw = om.createObjectNode().put("draft_id", draftId).put("generator_version", "mock-0.0.1")
                .put("produced_at", Instant.now().toString());
        raw.set("facts", facts);
        raw.set("narrative", narrative);
        return new StrDraftResponse(draftId, "mock-0.0.1", raw.get("produced_at").asText(), facts, narrative, raw);
    }

    @Override
    public VerifyResponse verify(VerifyRequest request) {
        ObjectNode raw = om.createObjectNode().put("verification_id", "VRF-mock-" + UUID.randomUUID().toString().substring(0, 6))
                .put("passed", true).put("verifier_version", "mock-0.0.1").put("produced_at", Instant.now().toString());
        raw.putArray("code_checks");
        raw.putArray("llm_checks");
        return new VerifyResponse(raw.get("verification_id").asText(), true, "mock-0.0.1", raw.get("produced_at").asText(), raw);
    }

    @Override
    public CaseSummaryResponse caseSummary(CaseSummaryRequest request) {
        ObjectNode raw = om.createObjectNode().put("summary", "(목업) 이 경보는 단기간 다수 계좌에서 입금이 집중된 유형입니다.")
                .put("generator_version", "mock-0.0.1").put("produced_at", Instant.now().toString());
        return new CaseSummaryResponse(raw.get("summary").asText(), "mock-0.0.1", raw.get("produced_at").asText(), raw);
    }

    @Override
    public HealthResponse health() {
        return new HealthResponse("ok", "mock-0.0.1", "mock-0.0.1");
    }
}
