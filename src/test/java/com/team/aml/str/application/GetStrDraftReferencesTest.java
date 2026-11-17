package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** GetStrDraft.references — 저장된 AI 응답에서 참고 사례 칸만 고르기. 스프링·DB 없이. */
class GetStrDraftReferencesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> refs(String payload) throws Exception {
        return GetStrDraft.references(JSON.readTree(payload));
    }

    // 1
    @Test
    void 정상_다섯_칸만_남기고_text와_모르는_칸은_뺀다() throws Exception {
        List<JsonNode> r = refs("""
                {"generation_meta": {"references": [
                  {"chunk_id": "c-1", "doc_id": "d-1", "doc_title": "사례집", "page": 12, "score": 0.83,
                   "text": "원문 조각", "extra": "x"}]}}
                """);

        assertThat(r).hasSize(1);
        assertThat(r.get(0).fieldNames()).toIterable()
                .containsExactly("chunk_id", "doc_id", "doc_title", "page", "score");
        assertThat(r.get(0).path("page").asInt()).isEqualTo(12);
    }

    // 2
    @Test
    void 정상_없는_칸은_키를_두고_null() throws Exception {
        List<JsonNode> r = refs("""
                {"generation_meta": {"references": [{"doc_id": "d-1", "doc_title": "사례집", "page": 3, "score": 0.7}]}}
                """);

        assertThat(r.get(0).get("chunk_id").isNull()).isTrue();
    }

    // 3
    @Test
    void 경계_references가_없거나_비었거나_배열이_아니거나_payload가_null이면_빈_목록() throws Exception {
        assertThat(refs("{}")).isEmpty();                                           // 예전 초안 (11/11 전)
        assertThat(refs("{\"generation_meta\": {\"references\": []}}")).isEmpty();  // RAG 결과 0건
        assertThat(refs("{\"generation_meta\": {\"references\": null}}")).isEmpty();
        assertThat(refs("{\"generation_meta\": {\"references\": \"x\"}}")).isEmpty();
        assertThat(GetStrDraft.references(null)).isEmpty();
    }

    // 4
    @Test
    void 경계_배열_안의_객체가_아닌_값은_건너뛴다() throws Exception {
        assertThat(refs("{\"generation_meta\": {\"references\": [1, null, {\"doc_id\": \"d\"}]}}")).hasSize(1);
    }
}
