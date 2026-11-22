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

    // --- SCRUM-291 질문 답변 — 질문 낱말로 상태 4종 ------------------------------------

    private AskRequest ask(String question) {
        return new AskRequest("CASE-1", alert(), List.of(1L, 2L, 3L), null, 0, null, question);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} → {1} {2} {3}")
    @org.junit.jupiter.params.provider.CsvSource(nullValues = "-", value = {
            "이 문장 수정해 줘,               OUT_OF_SCOPE, -,    EDIT_REQUEST",
            "다른 케이스도 같은 계좌야?,       OUT_OF_SCOPE, -,    OUTSIDE_CASE",
            "STR 보내야 하는지 판단해 줘,      OUT_OF_SCOPE, -,    JUDGMENT",
            "수정할지 판단해 줘,               OUT_OF_SCOPE, -,    EDIT_REQUEST",
            "자료에 없는 내용 알려 줘,          NO_DATA,      FACT, -",
            "숫자 불일치 나오게 해 줘,          UNVERIFIED,   FACT, -",
            "왜 위험한 거야?,                 ANSWERED,     WHY,  -",
            "입금 상대가 몇 곳이야?,           ANSWERED,     FACT, -"})
    void ask_picks_status_by_question_word_and_passes_response_check(String question, AskStatus status,
                                                                     AskKind kind, OutOfScopeReason reason) {
        AskRequest request = ask(question);
        AskResponse res = client.ask(request);

        assertThat(res.status()).isEqualTo(status);
        assertThat(res.kind()).isEqualTo(kind);
        assertThat(res.outOfScopeReason()).isEqualTo(reason);
        assertThat(res.caseNo()).isEqualTo("CASE-1");
        assertThat(res.producedAt()).endsWith("Z");
        WebClientAiClient.requireAskResponse(request, res.raw());   // 목업도 실제 응답 확인을 통과하는 모양
        if (status == AskStatus.ANSWERED) {
            assertThat(res.answer()).startsWith("(목업)");
            assertThat(res.cites()).isNotEmpty();
            assertThat(res.evidenceIds()).containsExactly(1L);   // 보낸 근거 안
            assertThat(res.generationMeta().promptVersion()).isNotNull();
        }
        if (status == AskStatus.OUT_OF_SCOPE) {
            assertThat(res.generationMeta().promptVersion()).isNull();
            assertThat(res.generationMeta().bundles()).isEmpty();
        }
    }

    @Test
    void ask_without_evidence_answers_with_empty_evidence_ids() {
        AskRequest request = new AskRequest("CASE-1", alert(), List.of(), null, 0, null, "몇 곳이야?");
        AskResponse res = client.ask(request);
        assertThat(res.status()).isEqualTo(AskStatus.ANSWERED);
        assertThat(res.evidenceIds()).isEmpty();
        WebClientAiClient.requireAskResponse(request, res.raw());
    }

    // --- SCRUM-309 참고 사례 검색 목업 ------------------------------------------------

    private static ReferenceSearchRequest search(int topK, String... typologies) {
        return new ReferenceSearchRequest(List.of(typologies), null, topK, List.of("PUBLIC"));
    }

    @Test
    void searchReferences_returns_three_found_results_that_pass_response_check() {
        ReferenceSearchRequest request = search(5, "FAN_IN");
        ReferenceSearchResponse res = client.searchReferences("DRF-000001", request);

        assertThat(res.status()).isEqualTo(ReferenceSearchStatus.FOUND);
        assertThat(res.results()).extracting(ReferenceResult::docId)
                .containsExactly("fintrac-underground-banking-2023", "ffiec-redflags", "fatf-pml-2018");
        assertThat(res.results()).extracting(ReferenceResult::score).containsExactly(0.52, 0.41, 0.33);
        assertThat(res.results()).allSatisfy(r -> {
            assertThat(r.typologies()).containsExactly("FAN_IN");   // 보낸 유형 그대로
            assertThat(r.sourceType()).isEqualTo("PUBLIC");
        });
        assertThat(res.results().get(1).year()).isNull();   // 예시 값 그대로
        assertThat(res.producedAt()).endsWith("Z");
        assertThat(res.searchMeta().candidates()).isEqualTo(3);
        WebClientAiClient.requireReferenceSearchResponse(request, res.raw());   // 목업도 실제 응답 확인을 통과
    }

    @Test
    void searchReferences_with_scatter_gather_is_empty_and_passes_response_check() {
        ReferenceSearchRequest request = search(5, "CYCLE", "SCATTER_GATHER");
        ReferenceSearchResponse res = client.searchReferences("DRF-000001", request);

        assertThat(res.status()).isEqualTo(ReferenceSearchStatus.EMPTY);
        assertThat(res.results()).isEmpty();
        assertThat(res.searchMeta().candidates()).isZero();
        WebClientAiClient.requireReferenceSearchResponse(request, res.raw());
    }

    @Test
    void searchReferences_cuts_to_top_k_when_smaller_than_three() {
        ReferenceSearchRequest request = search(2, "CYCLE");
        ReferenceSearchResponse res = client.searchReferences("DRF-000001", request);

        assertThat(res.results()).extracting(ReferenceResult::docId)
                .containsExactly("fintrac-underground-banking-2023", "ffiec-redflags");
        WebClientAiClient.requireReferenceSearchResponse(request, res.raw());
    }

    @Test
    void searchReferences_rejects_source_types_other_than_public_like_real_ai() {   // 검토 2 M7
        for (List<String> sources : List.of(List.of("APPROVED_STR"), List.of("PUBLIC", "APPROVED_STR"),
                List.of("PUBLIC", "PUBLIC"), List.<String>of())) {
            ReferenceSearchRequest request = new ReferenceSearchRequest(List.of("CYCLE"), null, 5, sources);
            Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> client.searchReferences("DRF-000001", request));
            assertThat(e).as(sources.toString()).isInstanceOfSatisfying(AiClientException.class, ae -> {
                assertThat(ae.code()).isEqualTo("AI_INVALID_REQUEST");
                assertThat(ae.httpStatus()).isEqualTo(502);
            });
        }
        ReferenceSearchResponse ok = client.searchReferences("DRF-000001", search(5, "CYCLE"));
        assertThat(ok.results()).extracting(ReferenceResult::sourceType).containsOnly("PUBLIC");
    }

    // --- SCRUM-357 승인 STR 색인 목업 ---------------------------------------------

    private static StrIndexRequest strIndex(List<String> typologies, String... texts) {
        String[] names = {"SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION"};
        List<StrIndexSection> sections = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            sections.add(new StrIndexSection(names[i], texts[i]));
        }
        return new StrIndexRequest("STR-2026-000012", "DRF-000312", "2026-11-24T01:00:00Z", typologies, sections);
    }

    private static final String LONG = "가".repeat(60);

    @Test
    void indexApprovedStr_indexes_non_blank_sections_and_passes_response_check() {
        StrIndexRequest request = strIndex(List.of("FAN_IN"), LONG, LONG, "   ");
        StrIndexResponse res = client.indexApprovedStr(request);

        assertThat(res.status()).isEqualTo(StrIndexStatus.INDEXED);
        assertThat(res.skipReason()).isNull();
        assertThat(res.chunkCount()).isEqualTo(2);   // 공백만인 섹션은 청크가 아님(AI 와 같음)
        assertThat(res.reportNo()).isEqualTo("STR-2026-000012");
        assertThat(res.retrievalVersion()).isNotBlank();
        assertThat(res.producedAt()).endsWith("Z");
        WebClientAiClient.requireStrIndexResponse(request, res.raw());
    }

    @Test
    void indexApprovedStr_scatter_gather_is_raw_account_before_too_short() {
        StrIndexRequest request = strIndex(List.of("FAN_IN", "SCATTER_GATHER"), "짧음");
        StrIndexResponse res = client.indexApprovedStr(request);

        assertThat(res.status()).isEqualTo(StrIndexStatus.SKIPPED);
        assertThat(res.skipReason()).isEqualTo(StrIndexSkipReason.RAW_ACCOUNT);   // AI 처럼 계좌 검사가 먼저
        assertThat(res.chunkCount()).isZero();
        WebClientAiClient.requireStrIndexResponse(request, res.raw());
    }

    @Test
    void indexApprovedStr_under_100_non_space_chars_is_too_short_boundary() {
        StrIndexRequest short99 = strIndex(List.of(), "가".repeat(50) + " \n\t", "가".repeat(49));
        StrIndexResponse skipped = client.indexApprovedStr(short99);
        assertThat(skipped.status()).isEqualTo(StrIndexStatus.SKIPPED);
        assertThat(skipped.skipReason()).isEqualTo(StrIndexSkipReason.TOO_SHORT);
        WebClientAiClient.requireStrIndexResponse(short99, skipped.raw());

        StrIndexRequest exact100 = strIndex(List.of(), "가".repeat(50), "가".repeat(50));
        assertThat(client.indexApprovedStr(exact100).status()).isEqualTo(StrIndexStatus.INDEXED);
    }
}
