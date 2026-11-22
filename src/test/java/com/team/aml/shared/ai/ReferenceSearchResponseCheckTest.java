package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-309 — 참고 사례 검색 응답 확인 `requireReferenceSearchResponse` (규약 5.5 + 보낸 값 대조).
 * 계약 예시(FOUND·EMPTY)를 기준으로 칸 하나씩 바꿔 본다. 위반은 모두 502 `AI_BAD_RESPONSE`, 메시지엔 칸 이름만.
 */
class ReferenceSearchResponseCheckTest {

    private static final Path EXAMPLES = Path.of("contracts/examples");
    private static final ObjectMapper OM = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static JsonNode example(String name) {
        try {
            return OM.readTree(Files.readString(EXAMPLES.resolve(name)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 보낸 요청 = `reference_search_request.json` (CYCLE · top_k 5 · PUBLIC). */
    private static ReferenceSearchRequest sent() {
        try {
            return OM.treeToValue(example("reference_search_request.json"), ReferenceSearchRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode body(String status) {
        return (ObjectNode) example("reference_search_response_" + status + ".json").deepCopy();
    }

    private static ObjectNode result(ObjectNode b, int i) {
        return (ObjectNode) b.get("results").get(i);
    }

    private static void check(ReferenceSearchRequest req, JsonNode b) {
        WebClientAiClient.requireReferenceSearchResponse(req, b);
    }

    // --- 정상 ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"found", "empty"})
    void 정상_계약_예시_두_상태는_통과한다(String status) {
        assertThatCode(() -> check(sent(), body(status))).doesNotThrowAnyException();
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_결과_수가_보낸_top_k와_같으면_통과한다() {
        ReferenceSearchRequest two = new ReferenceSearchRequest(List.of("CYCLE"), null, 2, List.of("PUBLIC"));
        assertThatCode(() -> check(two, body("found"))).doesNotThrowAnyException();
    }

    @Test
    void 경계_score_같은_값_연속과_양끝_1_과_마이너스1은_통과한다() {
        ObjectNode b = body("found");
        result(b, 0).put("score", 1.0);
        result(b, 1).put("score", 1.0);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
        result(b, 0).put("score", -1.0);
        result(b, 1).put("score", -1);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_결과_유형에_검색_5종_밖_태그가_섞여도_보낸_유형과_겹치면_통과한다() {
        ObjectNode b = body("found");
        result(b, 0).putArray("typologies").add("FX_RELAY").add("CYCLE");
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_보낸_유형이_여럿이면_그중_하나만_겹쳐도_통과한다() {
        ReferenceSearchRequest many = new ReferenceSearchRequest(List.of("FAN_IN", "GATHER_SCATTER"), null, 5,
                List.of("PUBLIC"));
        ObjectNode b = body("found");
        result(b, 1).putArray("typologies").add("FAN_IN");
        assertThatCode(() -> check(many, b)).doesNotThrowAnyException();   // 0번은 GATHER_SCATTER, 1번은 FAN_IN
    }

    @Test
    void 경계_모르는_칸과_publisher_year_null은_그대로_받는다() {
        ObjectNode b = body("found");
        b.put("새_칸", 1);
        result(b, 0).put("새_칸", "x");
        result(b, 0).putNull("publisher");
        result(b, 0).putNull("year");
        ((ObjectNode) b.get("search_meta")).put("새_칸", true);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_retrieval_version이_64자를_넘어도_응답_확인은_통과한다() {   // ai_version 만 null (WebClientAiClientTest)
        ObjectNode b = body("empty");
        b.put("retrieval_version", "v".repeat(65));
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    // --- 실패 ---------------------------------------------------------------

    static Stream<Arguments> violations() {
        return Stream.of(
                // 필수 칸
                bad("found", "produced_at", b -> b.remove("produced_at")),
                bad("found", "status", b -> b.putNull("status")),
                bad("found", "results", b -> b.remove("results")),
                bad("found", "results", b -> b.put("results", "[]")),
                bad("found", "retrieval_version", b -> b.remove("retrieval_version")),
                bad("found", "search_meta", b -> b.remove("search_meta")),
                // 상태·시각
                bad("found", "status", b -> b.put("status", "PARTIAL")),
                bad("found", "status", b -> b.put("status", "found")),
                bad("found", "produced_at", b -> b.put("produced_at", "2026-11-24T11:10:00+09:00")),
                bad("found", "produced_at", b -> b.put("produced_at", "2026-13-24T02:10:00Z")),
                bad("found", "produced_at", b -> b.put("produced_at", 20261124)),
                // 상태 ↔ 결과 수
                bad("found", "results", b -> b.putArray("results")),
                bad("empty", "results", b -> b.set("results", body("found").get("results"))),
                // 결과 수 ≤ 보낸 top_k (5)
                bad("found", "results", b -> {
                    ArrayNode arr = (ArrayNode) b.get("results");
                    for (int i = 0; i < 4; i++) arr.add(arr.get(1).deepCopy());
                }),
                // score
                bad("found", "score", b -> result(b, 1).put("score", 0.9)),   // 오름차순
                bad("found", "score", b -> result(b, 0).put("score", 1.01)),
                bad("found", "score", b -> result(b, 1).put("score", -1.01)),
                bad("found", "score", b -> result(b, 0).put("score", "0.52")),
                bad("found", "score", b -> result(b, 0).remove("score")),
                // 유형 겹침·출처
                bad("found", "typologies", b -> result(b, 1).putArray("typologies").add("FAN_IN")),
                bad("found", "typologies", b -> result(b, 1).putArray("typologies")),
                bad("found", "typologies", b -> result(b, 1).remove("typologies")),
                bad("found", "typologies", b -> result(b, 1).putArray("typologies").add(1)),
                bad("found", "source_type", b -> result(b, 0).put("source_type", "APPROVED_STR")),
                bad("found", "source_type", b -> result(b, 0).remove("source_type")),
                // 결과 칸 타입
                bad("found", "results", b -> ((ArrayNode) b.get("results")).set(1, OM.getNodeFactory().textNode("x"))),
                bad("found", "doc_id", b -> result(b, 0).remove("doc_id")),
                bad("found", "doc_title", b -> result(b, 0).put("doc_title", 3)),
                bad("found", "chunk_id", b -> result(b, 0).putNull("chunk_id")),
                bad("found", "page", b -> result(b, 0).put("page", "4")),
                bad("found", "page", b -> result(b, 0).put("page", 4.5)),
                bad("found", "page", b -> result(b, 0).remove("page")),
                bad("found", "publisher", b -> result(b, 0).put("publisher", 1)),
                bad("found", "publisher", b -> result(b, 0).remove("publisher")),
                bad("found", "year", b -> result(b, 0).put("year", "2023")),
                bad("found", "year", b -> result(b, 0).remove("year")),
                // retrieval_version · search_meta
                bad("empty", "retrieval_version", b -> b.put("retrieval_version", "")),
                bad("empty", "retrieval_version", b -> b.put("retrieval_version", 1)),
                bad("empty", "search_meta", b -> b.put("search_meta", "x")),
                bad("empty", "search_meta.degraded", b -> ((ObjectNode) b.get("search_meta")).put("degraded", "false")),
                bad("empty", "search_meta.candidates", b -> ((ObjectNode) b.get("search_meta")).put("candidates", -1)),
                bad("empty", "search_meta.candidates", b -> ((ObjectNode) b.get("search_meta")).remove("candidates")),
                bad("empty", "search_meta.latency_ms", b -> ((ObjectNode) b.get("search_meta")).put("latency_ms", 3.5)));
    }

    private static Arguments bad(String status, String field, Consumer<ObjectNode> edit) {
        return Arguments.of(status, field, edit);
    }

    @ParameterizedTest(name = "[{index}] {0} 의 {1}")
    @MethodSource("violations")
    void 실패_규칙을_어기면_AI_BAD_RESPONSE_502이고_메시지는_칸_이름만(String status, String field,
                                                             Consumer<ObjectNode> edit) {
        ObjectNode b = body(status);
        edit.accept(b);

        Throwable e = catchThrowable(() -> check(sent(), b));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).contains(field)
                    .doesNotContain(sent().query())                                       // 검색어 없음
                    .doesNotContain("Laundering");                                        // 문서 제목 없음
        });
    }

    @Test
    void 실패_결과_수는_AI_상한_10이_아니라_보낸_top_k로_잰다() {
        ReferenceSearchRequest one = new ReferenceSearchRequest(List.of("CYCLE"), null, 1, List.of("PUBLIC"));
        Throwable e = catchThrowable(() -> check(one, body("found")));
        assertThat(e).isInstanceOfSatisfying(AiClientException.class,
                ae -> assertThat(ae.getMessage()).contains("results"));
    }
}
