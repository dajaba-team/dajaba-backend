package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.StrIndexRequest;
import com.team.aml.shared.ai.AiDtos.StrIndexSection;
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
 * SCRUM-357 — 승인 STR 색인 응답 확인 `requireStrIndexResponse` (AI `str_index.py` 규칙 + 보낸 값 대조).
 * 계약 예시(INDEXED·SKIPPED)를 기준으로 칸 하나씩 바꿔 본다. 위반은 모두 502 `AI_BAD_RESPONSE`, 메시지엔 칸 이름만.
 */
class StrIndexResponseCheckTest {

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

    /** 보낸 요청 = `str_index_request.json` (STR-2026-000012 · 섹션 4개). */
    private static StrIndexRequest sent() {
        try {
            return OM.treeToValue(example("str_index_request.json"), StrIndexRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** SKIPPED 예시는 다른 문서번호라, 보낸 요청의 번호로 맞춘 몸통. */
    private static ObjectNode body(String status) {
        ObjectNode b = (ObjectNode) example("str_index_response_" + status + ".json").deepCopy();
        b.put("report_no", "STR-2026-000012");
        return b;
    }

    private static void check(StrIndexRequest req, JsonNode b) {
        WebClientAiClient.requireStrIndexResponse(req, b);
    }

    private static void assertBad(Throwable e, String field) {
        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).contains(field).doesNotContain("보고 대상자");
        });
    }

    // --- 정상 ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"indexed", "skipped"})
    void 정상_계약_예시_두_상태는_통과한다(String status) {
        assertThatCode(() -> check(sent(), body(status))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RAW_ACCOUNT", "TOO_SHORT", "DISABLED", "NO_FACTS"})
    void 정상_건너뛴_이유_네_가지는_모두_통과한다(String reason) {
        ObjectNode b = body("skipped");
        b.put("skip_reason", reason);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_SKIPPED_는_retrieval_version_이_null_이어도_통과한다() {
        ObjectNode b = body("skipped");
        b.put("skip_reason", "DISABLED");
        b.putNull("retrieval_version");
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_SKIPPED_는_버전이_공백뿐이어도_통과하고_INDEXED_는_앞뒤_공백이_있어도_글자가_남으면_통과한다() {
        ObjectNode skipped = body("skipped");
        skipped.put("retrieval_version", "  ");
        assertThatCode(() -> check(sent(), skipped)).doesNotThrowAnyException();
        ObjectNode indexed = body("indexed");
        indexed.put("retrieval_version", " text-embedding-3-small@36b5a2d5 ");
        assertThatCode(() -> check(sent(), indexed)).doesNotThrowAnyException();
    }

    @Test
    void 경계_INDEXED_chunk_count_는_1과_보낸_섹션_수까지_통과한다() {
        ObjectNode b = body("indexed");
        b.put("chunk_count", 1);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
        b.put("chunk_count", sent().sections().size());
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    @Test
    void 경계_계약_밖_칸이_더_있어도_통과한다() {
        ObjectNode b = body("indexed");
        b.put("latency_ms", 12);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    // --- 실패 ---------------------------------------------------------------

    static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("INDEXED + 이유", "skip_reason", (Consumer<ObjectNode>) b -> b.put("skip_reason", "TOO_SHORT"), "indexed"),
                Arguments.of("INDEXED 0건", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", 0), "indexed"),
                Arguments.of("INDEXED 섹션 수 초과", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", 5), "indexed"),
                Arguments.of("INDEXED 음수", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", -1), "indexed"),
                Arguments.of("INDEXED 버전 null", "retrieval_version", (Consumer<ObjectNode>) b -> b.putNull("retrieval_version"), "indexed"),
                Arguments.of("INDEXED 버전 빈 값", "retrieval_version", (Consumer<ObjectNode>) b -> b.put("retrieval_version", ""), "indexed"),
                Arguments.of("INDEXED 버전 공백뿐", "retrieval_version", (Consumer<ObjectNode>) b -> b.put("retrieval_version", " \t\n"), "indexed"),
                Arguments.of("버전 숫자", "retrieval_version", (Consumer<ObjectNode>) b -> b.put("retrieval_version", 3), "skipped"),
                Arguments.of("SKIPPED 이유 없음", "skip_reason", (Consumer<ObjectNode>) b -> b.putNull("skip_reason"), "skipped"),
                Arguments.of("SKIPPED chunk 1", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", 1), "skipped"),
                Arguments.of("report_no 다름", "report_no", (Consumer<ObjectNode>) b -> b.put("report_no", "STR-2026-000099"), "indexed"),
                Arguments.of("report_no 숫자", "report_no", (Consumer<ObjectNode>) b -> b.put("report_no", 12), "indexed"),
                Arguments.of("모르는 상태", "status", (Consumer<ObjectNode>) b -> b.put("status", "QUEUED"), "indexed"),
                Arguments.of("모르는 이유", "skip_reason", (Consumer<ObjectNode>) b -> b.put("skip_reason", "BLOCKED"), "skipped"),
                Arguments.of("이유 숫자", "skip_reason", (Consumer<ObjectNode>) b -> b.put("skip_reason", 1), "skipped"),
                Arguments.of("produced_at 오프셋", "produced_at", (Consumer<ObjectNode>) b -> b.put("produced_at", "2026-11-24T10:00:03+09:00"), "indexed"),
                Arguments.of("produced_at 날짜 아님", "produced_at", (Consumer<ObjectNode>) b -> b.put("produced_at", "어제Z"), "indexed"),
                Arguments.of("chunk 소수", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", 1.5), "indexed"),
                Arguments.of("chunk 문자열", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", "4"), "indexed"),
                Arguments.of("chunk 참거짓", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", true), "indexed"),
                Arguments.of("produced_at 없음", "produced_at", (Consumer<ObjectNode>) b -> b.remove("produced_at"), "indexed"),
                Arguments.of("report_no 없음", "report_no", (Consumer<ObjectNode>) b -> b.remove("report_no"), "indexed"),
                Arguments.of("status 없음", "status", (Consumer<ObjectNode>) b -> b.remove("status"), "indexed"),
                Arguments.of("skip_reason 없음", "skip_reason", (Consumer<ObjectNode>) b -> b.remove("skip_reason"), "indexed"),
                Arguments.of("chunk_count 없음", "chunk_count", (Consumer<ObjectNode>) b -> b.remove("chunk_count"), "indexed"),
                Arguments.of("retrieval_version 없음", "retrieval_version", (Consumer<ObjectNode>) b -> b.remove("retrieval_version"), "skipped"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void 실패_어긋나면_AI_BAD_RESPONSE_이고_메시지엔_칸_이름만(String name, String field, Consumer<ObjectNode> change,
                                                        String status) {
        ObjectNode b = body(status);
        change.accept(b);
        assertBad(catchThrowable(() -> check(sent(), b)), field);
    }

    @Test
    void 실패_본문이_객체가_아니면_AI_BAD_RESPONSE() {
        assertBad(catchThrowable(() -> check(sent(), OM.createArrayNode())), "body");
    }

    @Test
    void 실패_섹션_하나를_보냈는데_청크_2개면_AI_BAD_RESPONSE() {
        // AI 는 섹션 하나 = 청크 하나(공백만인 섹션은 뺌) → chunk_count 는 보낸 섹션 수를 넘을 수 없다
        StrIndexRequest one = new StrIndexRequest("STR-2026-000012", "DRF-000312", "2026-11-24T01:00:00Z", List.of(),
                List.of(new StrIndexSection("SUBJECT", "보고 대상자 설명")));
        ObjectNode b = body("indexed");
        b.put("chunk_count", 2);
        assertBad(catchThrowable(() -> check(one, b)), "chunk_count");
    }
}
