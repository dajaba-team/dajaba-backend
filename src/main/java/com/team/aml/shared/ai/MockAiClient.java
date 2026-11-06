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
 * 점수는 alert_id 해시로 0.05~0.95 사이를 결정적으로 돌려준다 → 같은 경보는 항상 같은 점수.
 * 초안·검증·요약은 규약 §5 모양의 최소 JSON. 진짜 서버를 붙이려면 aml.ai.mock=false + AI_BASE_URL.
 *
 * SCRUM-151: 초안 응답은 v2 7항목(SUBJECT·DATE·PLACE·INSTRUMENT·METHOD·GROUNDS·OPINION), 섹션마다
 * `cites` 1개 이상, `draft_id` 는 요청 값 그대로, `generation_meta.prompt_version="narrative_v2"`.
 */
@Component
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "true")
public class MockAiClient implements AiClient {

    /** v2 7항목 순서 — 규약 §5.2. shared 수준 String(StrSection enum 참조 금지, ArchitectureTest). */
    private static final List<String> V2_SECTIONS = List.of(
            "SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION");
    /** 섹션마다 cites 1개 이상 — 채영 cites 저장 테스트 재료 (SCRUM-151). */
    private static final List<String> SECTION_CITES = List.of(
            "subject", "period", "subject.account_key", "payment_formats",
            "counterparties.count", "observations", "typology");

    /** 목업 응답의 추천 임계값 — AI 예시(`score_response.json`)와 같은 값. 운영 경계는 CasePolicy. */
    private static final double MOCK_THRESHOLD = 0.7;

    private final ObjectMapper om;

    public MockAiClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ScoresResponse scores(long batchRunId, List<AlertPayload> alerts) {
        // SCRUM-128: 규약 §3 모양 — 묶음 하나로 보고 scoring_meta·produced_at 을 항목마다 붙임. 항목은 전부 OK.
        ScoringMeta meta = new ScoringMeta("mock-0.0.1", "mock-f0", "mock", 0);
        String producedAt = Instant.now().toString();
        List<ScoreResult> out = new ArrayList<>();
        for (AlertPayload a : alerts) {
            double score = 0.05 + (Math.abs(a.alertId().hashCode()) % 90) / 100.0;   // 0.05 ~ 0.94
            String predictionId = "PRD-mock-" + Math.abs(a.alertId().hashCode());
            List<Long> evidence = a.transactionIds() == null ? List.of() : List.copyOf(a.transactionIds());
            ObjectNode raw = om.createObjectNode()
                    .put("alert_id", a.alertId()).put("prediction_id", predictionId)
                    .put("score", score).put("threshold", MOCK_THRESHOLD)
                    .put("data_quality_status", "OK");
            raw.putNull("failure_code");
            raw.putArray("reasons");
            evidence.forEach(raw.putArray("evidence_transaction_ids")::add);
            raw.putArray("context_transaction_ids");
            out.add(new ScoreResult(a.alertId(), predictionId, score, MOCK_THRESHOLD, "OK", null,
                    raw.get("reasons"), evidence, List.of(), meta, producedAt, raw));
        }
        return new ScoresResponse(out);
    }

    @Override
    public StrDraftResponse createDraft(StrDraftRequest request) {
        String draftId = request.draftId();   // SCRUM-151: 요청 값 그대로
        String generationId = "GEN-mock-" + UUID.randomUUID().toString().substring(0, 6);
        String producedAt = Instant.now().toString();

        List<NarrativeSectionDto> narrative = new ArrayList<>();
        for (int i = 0; i < V2_SECTIONS.size(); i++) {
            String section = V2_SECTIONS.get(i);
            narrative.add(new NarrativeSectionDto(
                    section,
                    "(목업) " + section + " 섹션 문장",
                    List.of(SECTION_CITES.get(i)),
                    List.of()));
        }

        ObjectNode generationMeta = om.createObjectNode()
                .put("llm_model", "mock-0.0.1")
                .put("prompt_version", "narrative_v2");
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("draft_id", draftId)
                .put("generation_id", generationId);
        raw.set("facts", om.createObjectNode());
        raw.set("narrative", om.valueToTree(narrative));
        raw.set("generation_meta", generationMeta);
        raw.putNull("verification");

        return new StrDraftResponse(
                producedAt, draftId, generationId, narrative,
                new StrDraftResponse.GenerationMetaView("narrative_v2"),
                om.createObjectNode(), null, raw);
    }

    @Override
    public VerifyResponse verify(String draftId, VerifyRequest request) {
        String verificationId = "VRF-mock-" + UUID.randomUUID().toString().substring(0, 6);
        String producedAt = Instant.now().toString();
        ObjectNode raw = om.createObjectNode()
                .put("verification_id", verificationId)
                .put("generation_id", request.generationId())
                .put("revision_no", request.revisionNo() == null ? 1 : request.revisionNo())
                .put("narrative_sha256", "0".repeat(64))
                .put("passed", true)
                .put("status", "PASSED")
                .put("verifier_version", "mock-0.0.1")
                .put("produced_at", producedAt);
        raw.putArray("code_checks");
        raw.putArray("llm_checks");
        raw.putNull("narrative_refs");
        raw.putNull("judge_meta");

        return new VerifyResponse(
                verificationId, request.generationId(),
                request.revisionNo() == null ? 1 : request.revisionNo(),
                "0".repeat(64), true, "PASSED", "mock-0.0.1", producedAt, raw);
    }

    @Override
    public CaseSummaryResponse caseSummary(CaseSummaryRequest request) {
        // 규약 v0.4 §6.1 응답 모양. 코드 검사는 전부 통과로 둔다(목업).
        String producedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        String generationId = "GEN-mock-" + UUID.randomUUID().toString().substring(0, 6);
        List<String> lines = List.of("(목업) 이 경보는 단기간 다수 계좌에서 입금이 집중된 유형입니다.",
                "(목업) 근거 거래 " + (request.evidenceTransactionIds() == null ? 0 : request.evidenceTransactionIds().size())
                        + "건을 확인해 주세요.");
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", producedAt)
                .put("case_no", request.caseNo())
                .put("generation_id", generationId);
        raw.putObject("facts");
        lines.forEach(raw.putArray("summary_lines")::add);
        raw.putArray("model_focus");
        raw.putArray("code_checks").addObject().put("check", "COUNT_MATCH").put("match", true);
        raw.putObject("generation_meta").put("prompt_version", "mock-0.0.1");
        return new CaseSummaryResponse(producedAt, request.caseNo(), generationId, lines,
                raw.get("code_checks"), new StrDraftResponse.GenerationMetaView("mock-0.0.1"), raw);
    }

    @Override
    public HealthResponse health() {
        return new HealthResponse("ok", "ok", "ok", "mock", "mock-0.0.1", "mock-f0", "mock-r0");
    }
}
