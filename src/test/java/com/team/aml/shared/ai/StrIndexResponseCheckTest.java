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
 * SCRUM-361 — INDEXED 청크 수 상한 = 공백만이 아닌 섹션마다 ⌊글자 수(코드 포인트) ÷ 600⌋ + 1 의 합.
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
    void 경계_INDEXED_chunk_count_는_1과_상한까지_통과한다() {
        // 예시 섹션 4개는 모두 600자 미만 → 섹션마다 몫 1 → 상한 4 (옛 모양: 청크 수 = 섹션 수)
        ObjectNode b = body("indexed");
        b.put("chunk_count", 1);
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
        b.put("chunk_count", sent().sections().size());
        assertThatCode(() -> check(sent(), b)).doesNotThrowAnyException();
    }

    // --- 청크 수 상한: 공백만이 아닌 섹션마다 ⌊글자 수 ÷ 600⌋ + 1 의 합 (SCRUM-361) ---

    private static final List<String> SEVEN = List.of("SUBJECT", "DATE", "PLACE", "PARTIES", "METHOD", "GROUNDS", "OTHER");

    /** 섹션 이름은 순서대로 붙인다(값 검사는 AI 몫이라 이름은 상관없음). null 은 글 null. */
    private static StrIndexRequest sending(String... texts) {
        java.util.ArrayList<StrIndexSection> sections = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) sections.add(new StrIndexSection(SEVEN.get(i), texts[i]));
        return new StrIndexRequest("STR-2026-000012", "DRF-000312", "2026-11-24T01:00:00Z", List.of(), sections);
    }

    private static ObjectNode indexedWith(int chunks) {
        ObjectNode b = body("indexed");
        b.put("chunk_count", chunks);
        return b;
    }

    private static void assertBound(StrIndexRequest req, int bound) {
        assertThatCode(() -> check(req, indexedWith(bound))).doesNotThrowAnyException();
        assertBad(catchThrowable(() -> check(req, indexedWith(bound + 1))), "chunk_count");
    }

    @ParameterizedTest(name = "{0}자 → 상한 {1}")
    @org.junit.jupiter.params.provider.CsvSource({"1,1", "599,1", "600,2", "1199,2", "1200,3", "5000,9"})
    void 경계_단락_하나의_상한은_글자_수_나누기_600_내림_더하기_1(int length, int bound) {
        assertBound(sending("가".repeat(length)), bound);
    }

    @Test
    void 정상_섹션_수보다_큰_청크_수도_상한_안이면_통과한다() {
        // 2500자(몫 5) + 1300자(몫 3) → 상한 8, 섹션은 둘
        StrIndexRequest req = sending("나".repeat(2500), "다".repeat(1300));
        assertThatCode(() -> check(req, indexedWith(5))).doesNotThrowAnyException();
        assertBound(req, 8);
    }

    @Test
    void 정상_옛_모양_청크_수_공백_아닌_섹션_수는_긴_단락이_섞여도_통과한다() {
        StrIndexRequest req = sending("라".repeat(5000), "짧은 단락", " \n ", "마".repeat(1200));
        assertThatCode(() -> check(req, indexedWith(3))).doesNotThrowAnyException();
    }

    @Test
    void 경계_일곱_섹션_5000자씩이면_상한_63() {
        assertBound(sending(java.util.Collections.nCopies(7, "바".repeat(5000)).toArray(String[]::new)), 63);
    }

    @Test
    void 경계_글자_수는_코드_포인트로_센다_이모지_600개는_600자() {
        // UTF-16 으로 세면 1200 → 상한 3 이 되지만 AI(Python len)는 600 → 상한 2
        assertBound(sending("😀".repeat(600)), 2);
        assertBound(sending("😀".repeat(599)), 1);
    }

    @Test
    void 경계_공백만인_섹션과_null_글은_0으로_센다() {
        // 파이썬 공백(전각 공백·NEL 포함)만인 섹션은 AI 가 청크를 안 만든다 — 1200자(몫 3)만 셈
        assertBound(sending("사".repeat(1200), " \t\n", "　\u0085", null), 3);
    }

    @Test
    void 경계_앞뒤_공백도_글자_수에_넣는다() {
        // 원문 그대로 셈: 글 599자 + 앞뒤 공백 1자씩 = 601자 → 상한 2
        assertBound(sending(" " + "아".repeat(599) + " "), 2);
    }

    @Test
    void 실패_섹션_목록이_null_이면_상한_0이라_INDEXED_는_AI_BAD_RESPONSE() {
        StrIndexRequest none = new StrIndexRequest("STR-2026-000012", "DRF-000312", "2026-11-24T01:00:00Z", List.of(), null);
        assertBad(catchThrowable(() -> check(none, indexedWith(1))), "chunk_count");
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
                Arguments.of("INDEXED 상한 초과", "chunk_count", (Consumer<ObjectNode>) b -> b.put("chunk_count", 5), "indexed"),
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
    void 실패_짧은_섹션_하나를_보냈는데_청크_2개면_AI_BAD_RESPONSE() {
        // 600자 미만 섹션 하나 → 상한 1 (AI 는 1200자 안쪽이면 나누지 않음)
        StrIndexRequest one = new StrIndexRequest("STR-2026-000012", "DRF-000312", "2026-11-24T01:00:00Z", List.of(),
                List.of(new StrIndexSection("SUBJECT", "보고 대상자 설명")));
        ObjectNode b = body("indexed");
        b.put("chunk_count", 2);
        assertBad(catchThrowable(() -> check(one, b)), "chunk_count");
    }
}
