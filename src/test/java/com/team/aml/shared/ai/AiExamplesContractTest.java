package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        // AI 고쳐 쓰기 기록(AI #39, SCRUM-147) — 백엔드는 해석하지 않고 raw 그대로 str_drafts.payload 에 저장한다
        assertThat(dto.raw().at("/generation_meta/rewrite/skipped_reason").asText()).isEqualTo("not_triggered");
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

    // --- SCRUM-128 점수 응답 (규약 §3) -------------------------------------------

    @Test
    void score_response_fields_populated_and_raw_has_original() throws Exception {
        JsonNode original = readExample("score_response.json");
        List<ScoreResult> items = WebClientAiClient.readScoreBatch(om, original);
        assertThat(items).hasSize(2);

        ScoreResult ok = items.get(0);
        assertThat(ok.alertId()).isEqualTo("ALT-20220903-000142");
        assertThat(ok.predictionId()).isEqualTo("p-9f3a2c");
        assertThat(ok.score()).isEqualTo(0.91);
        assertThat(ok.threshold()).isEqualTo(0.7);
        assertThat(ok.dataQualityStatus()).isEqualTo("OK");
        assertThat(ok.failureCode()).isNull();
        assertThat(ok.reasons()).isEqualTo(original.at("/scores/0/reasons"));
        assertThat(ok.evidenceTransactionIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(ok.contextTransactionIds()).containsExactly(103998L, 104002L);
        assertThat(ok.raw()).isEqualTo(original.at("/scores/0"));   // 항목 원문 그대로

        ScoreResult failed = items.get(1);
        assertThat(failed.alertId()).isEqualTo("ALT-20220903-000143");
        assertThat(failed.predictionId()).isNull();
        assertThat(failed.score()).isNull();
        assertThat(failed.threshold()).isEqualTo(0.7);
        assertThat(failed.dataQualityStatus()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(failed.reasons()).isEmpty();
        assertThat(failed.evidenceTransactionIds()).isEmpty();
        assertThat(failed.contextTransactionIds()).isEmpty();
        assertThat(failed.raw()).isEqualTo(original.at("/scores/1"));

        for (ScoreResult r : items) {   // 맨 위 scoring_meta 네 칸 + produced_at 이 항목마다
            assertThat(r.meta().modelVersion()).isEqualTo("xgb-2026-11-05");
            assertThat(r.meta().featureVersion()).isEqualTo("f1");
            assertThat(r.meta().serverVersion()).isEqualTo("a1b2c3d");
            assertThat(r.meta().latencyMs()).isEqualTo(1840);
            assertThat(r.producedAt()).isEqualTo("2026-11-03T01:05:00Z");
        }
        assertThat(ok.meta()).isSameAs(failed.meta());   // 같은 묶음은 같은 객체
    }

    @Test
    void score_response_scoring_meta_roundtrip() throws Exception {
        JsonNode original = readExample("score_response.json").get("scoring_meta");
        ScoringMeta dto = om.treeToValue(original, ScoringMeta.class);
        assertThat(om.valueToTree(dto).toString()).isEqualTo(original.toString());
    }

    // --- SCRUM-128 요약 예시 (규약 v0.4 §6.1) · MANIFEST ---------------------------

    @Test
    void summary_request_roundtrip() throws Exception {
        JsonNode original = readExample("summary_request.json");
        CaseSummaryRequest dto = om.treeToValue(original, CaseSummaryRequest.class);
        assertThat(om.valueToTree(dto).toString()).isEqualTo(original.toString());
        assertThat(dto.caseNo()).isEqualTo("CASE-2022-000057");
        assertThat(dto.alert().alertId()).isEqualTo("ALT-20220903-000142");
        assertThat(dto.evidenceTransactionIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(dto.priorAlertCount()).isEqualTo(1);
        assertThat(dto.reportingInstitution()).isEqualTo("Portugal Bank #4507");
    }

    @Test
    void summary_response_fields_populated_and_raw_has_original() throws Exception {
        JsonNode original = readExample("summary_response.json");
        CaseSummaryResponse dto = om.treeToValue(withRaw(original), CaseSummaryResponse.class);
        assertThat(dto.producedAt()).isEqualTo("2026-11-06T05:20:00Z");
        assertThat(dto.caseNo()).isEqualTo("CASE-2022-000057");
        assertThat(dto.generationId()).isEqualTo("g-2b7c91");
        assertThat(dto.summaryLines()).hasSize(2);
        assertThat(dto.codeChecks()).hasSize(4);
        assertThat(dto.codeChecks().get(0).get("match").asBoolean()).isTrue();
        assertThat(dto.promptVersion()).isEqualTo("summary_v1");
        assertThat(dto.raw().toString()).isEqualTo(original.toString());   // model_focus 등 안 쓰는 칸도 원문에
    }

    // --- SCRUM-291 질문 답변 예시 (규약 5.4) ----------------------------------------

    @Test
    void ask_request_roundtrip_and_of_summary_request_is_same_body() throws Exception {
        JsonNode original = readExample("ask_request.json");
        AskRequest dto = om.treeToValue(original, AskRequest.class);
        assertThat(om.valueToTree(dto).toString()).isEqualTo(original.toString());
        assertThat(dto.question()).isEqualTo("입금 상대가 몇 곳이야?");

        // 요약 요청 6칸 + question — 요약 조립을 그대로 쓰는 편의 생성
        CaseSummaryRequest summary = om.treeToValue(readExample("summary_request.json"), CaseSummaryRequest.class);
        JsonNode viaOf = om.valueToTree(AskRequest.of(summary, dto.question()));
        ObjectNode expected = (ObjectNode) om.valueToTree(summary);
        expected.put("question", dto.question());
        assertThat(viaOf).isEqualTo(expected);
    }

    @Test
    void ask_request_keeps_question_whitespace() throws Exception {
        CaseSummaryRequest summary = om.treeToValue(readExample("summary_request.json"), CaseSummaryRequest.class);
        JsonNode sent = om.valueToTree(AskRequest.of(summary, "  몇 곳이야? \n"));
        assertThat(sent.get("question").textValue()).isEqualTo("  몇 곳이야? \n");   // 자르지 않음(규약 5.4)
    }

    @Test
    void ask_response_answered_fields_populated_and_raw_has_original() throws Exception {
        JsonNode original = readExample("ask_response_answered.json");
        WebClientAiClient.requireAskResponse(om.treeToValue(readExample("ask_request.json"), AskRequest.class), original);
        AskResponse dto = om.treeToValue(withRaw(original), AskResponse.class);
        assertThat(dto.producedAt()).isEqualTo("2026-11-27T02:10:00Z");
        assertThat(dto.caseNo()).isEqualTo("CASE-2022-000057");
        assertThat(dto.generationId()).isEqualTo("g-91ab33c0");
        assertThat(dto.status()).isEqualTo(AskStatus.ANSWERED);
        assertThat(dto.kind()).isEqualTo(AskKind.FACT);
        assertThat(dto.outOfScopeReason()).isNull();
        assertThat(dto.answer()).isEqualTo("입금 상대 계좌는 3개예요.");
        assertThat(dto.cites()).containsExactly("counterparties.count");
        assertThat(dto.evidenceIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(dto.codeChecks().get(0).get("match").asBoolean()).isTrue();
        AskGenerationMeta meta = dto.generationMeta();
        assertThat(meta.llmModel()).isEqualTo("(API가 돌려준 모델 ID)");
        assertThat(meta.promptVersion()).isEqualTo("ask_answer_v1");
        assertThat(meta.planPromptVersion()).isEqualTo("ask_plan_v1");
        assertThat(meta.bundles()).containsExactly("counterparties");
        assertThat(meta.serverVersion()).isEqualTo("a1b2c3d");
        assertThat(meta.tokensIn()).isEqualTo(1450);
        assertThat(meta.tokensOut()).isEqualTo(120);
        assertThat(meta.latencyMs()).isEqualTo(6200);
        assertThat((JsonNode) om.valueToTree(meta)).isEqualTo(original.get("generation_meta"));   // 8칸 빠짐·남음 없음
        assertThat(dto.raw().toString()).isEqualTo(original.toString());
    }

    @Test
    void ask_response_other_statuses_read_into_dto() throws Exception {
        AskRequest sent = om.treeToValue(readExample("ask_request.json"), AskRequest.class);
        record Want(String file, AskStatus status, AskKind kind, OutOfScopeReason reason, String promptVersion) {}
        for (Want w : List.of(
                new Want("ask_response_no_data.json", AskStatus.NO_DATA, AskKind.FACT, null, "ask_answer_v1"),
                new Want("ask_response_unverified.json", AskStatus.UNVERIFIED, AskKind.FACT, null, "ask_answer_v1"),
                new Want("ask_response_out_of_scope.json", AskStatus.OUT_OF_SCOPE, null, OutOfScopeReason.JUDGMENT, null))) {
            JsonNode original = readExample(w.file());
            WebClientAiClient.requireAskResponse(sent, original);
            AskResponse dto = om.treeToValue(withRaw(original), AskResponse.class);
            assertThat(dto.status()).as(w.file()).isEqualTo(w.status());
            assertThat(dto.kind()).as(w.file()).isEqualTo(w.kind());
            assertThat(dto.outOfScopeReason()).as(w.file()).isEqualTo(w.reason());
            assertThat(dto.answer()).as(w.file()).isNull();
            assertThat(dto.cites()).as(w.file()).isEmpty();
            assertThat(dto.evidenceIds()).as(w.file()).isEmpty();
            assertThat(dto.generationMeta().promptVersion()).as(w.file()).isEqualTo(w.promptVersion());
            assertThat(dto.generationMeta().planPromptVersion()).as(w.file()).isEqualTo("ask_plan_v1");
            assertThat(dto.raw().toString()).as(w.file()).isEqualTo(original.toString());
        }
    }

    // --- SCRUM-309 참고 사례 검색 예시 (규약 5.5) --------------------------------------

    @Test
    void reference_search_request_roundtrip() throws Exception {
        JsonNode original = readExample("reference_search_request.json");
        ReferenceSearchRequest dto = om.treeToValue(original, ReferenceSearchRequest.class);
        assertThat(om.valueToTree(dto).toString()).isEqualTo(original.toString());
        assertThat(dto.typologies()).containsExactly("CYCLE");
        assertThat(dto.topK()).isEqualTo(5);
        assertThat(dto.sourceTypes()).containsExactly("PUBLIC");

        JsonNode nullQuery = om.valueToTree(new ReferenceSearchRequest(List.of("CYCLE"), null, 5, List.of("PUBLIC")));
        assertThat(nullQuery.has("query")).isTrue();   // 빈 검색어는 null 로 나감(AI 기본값과 같음)
        assertThat(nullQuery.get("query").isNull()).isTrue();
    }

    @Test
    void reference_search_responses_pass_check_and_read_into_dto() throws Exception {
        ReferenceSearchRequest sent = om.treeToValue(readExample("reference_search_request.json"), ReferenceSearchRequest.class);

        JsonNode found = readExample("reference_search_response_found.json");
        WebClientAiClient.requireReferenceSearchResponse(sent, found);
        ReferenceSearchResponse f = om.treeToValue(withRaw(found), ReferenceSearchResponse.class);
        assertThat(f.producedAt()).isEqualTo("2026-11-24T02:10:00Z");
        assertThat(f.status()).isEqualTo(ReferenceSearchStatus.FOUND);
        assertThat(f.retrievalVersion()).isEqualTo("text-embedding-3-small@36b5a2d5");
        assertThat(f.searchMeta()).isEqualTo(new ReferenceSearchMeta(false, 12, 420));
        assertThat(f.results()).hasSize(2);
        ReferenceResult first = f.results().get(0);
        assertThat(first.docId()).isEqualTo("fintrac-underground-banking-2023");
        assertThat(first.publisher()).isEqualTo("FINTRAC");
        assertThat(first.year()).isEqualTo(2023);
        assertThat(first.page()).isEqualTo(4);
        assertThat(first.score()).isEqualTo(0.52);
        assertThat(first.typologies()).containsExactly("CYCLE", "GATHER_SCATTER");
        assertThat(first.sourceType()).isEqualTo("PUBLIC");
        assertThat(f.results().get(1).year()).isNull();
        for (int i = 0; i < 2; i++) {   // 결과 9칸 빠짐·남음 없음
            assertThat((JsonNode) om.valueToTree(f.results().get(i))).isEqualTo(found.get("results").get(i));
        }
        assertThat(f.raw().toString()).isEqualTo(found.toString());

        JsonNode empty = readExample("reference_search_response_empty.json");
        WebClientAiClient.requireReferenceSearchResponse(sent, empty);
        ReferenceSearchResponse e = om.treeToValue(withRaw(empty), ReferenceSearchResponse.class);
        assertThat(e.status()).isEqualTo(ReferenceSearchStatus.EMPTY);
        assertThat(e.results()).isEmpty();
        assertThat(e.searchMeta()).isEqualTo(new ReferenceSearchMeta(false, 0, 35));
        assertThat(e.raw().toString()).isEqualTo(empty.toString());
    }

    /** contracts/MANIFEST.sha256 = AI 리포 기준 커밋의 해시. 사본이 몰래 바뀌거나 MANIFEST 에 빠진 예시가 있으면 깨진다. */
    @Test
    void manifest_hashes_match_copies_and_cover_every_example() throws Exception {
        Path contracts = EXAMPLES.getParent();
        Map<String, String> manifest = new TreeMap<>();
        for (String line : Files.readAllLines(contracts.resolve("MANIFEST.sha256"))) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\s+", 2);
            manifest.put(parts[1].trim(), parts[0]);
        }
        Set<String> examples = new TreeSet<>();
        try (var files = Files.list(EXAMPLES)) {
            files.map(p -> "examples/" + p.getFileName()).filter(n -> n.endsWith(".json")).forEach(examples::add);
        }

        assertThat(manifest.keySet()).isEqualTo(examples);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (var e : manifest.entrySet()) {
            String actual = HexFormat.of().formatHex(sha.digest(Files.readAllBytes(contracts.resolve(e.getKey()))));
            assertThat(actual).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void score_payload_for_storage_is_item_plus_scoring_meta_and_produced_at() throws Exception {
        JsonNode original = readExample("score_response.json");
        ScoreResult ok = WebClientAiClient.readScoreBatch(om, original).get(0);

        ObjectNode expected = (ObjectNode) original.at("/scores/0").deepCopy();
        expected.set("scoring_meta", original.get("scoring_meta"));
        expected.set("produced_at", original.get("produced_at"));

        assertThat(ok.payloadForStorage()).isEqualTo(expected);
        assertThat(ok.raw()).isEqualTo(original.at("/scores/0"));   // raw 는 바뀌지 않음
    }
}
