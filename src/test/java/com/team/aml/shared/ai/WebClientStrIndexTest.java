package com.team.aml.shared.ai;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import com.team.aml.shared.config.AmlProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-357 — `WebClientAiClient.indexApprovedStr` (`POST /v1/references/str`). 호출·재시도 루프는 참고 사례 검색과 같은
 * {@code callSelfBudgeted} — 여기서는 색인 쪽 경로·예산(30초 기본)·기록 칸·오류 문구·응답 확인·로그에 섹션 글이 없는지를 본다.
 */
class WebClientStrIndexTest {

    private static final String KEY = "test-internal-key-0123456789abcd";
    private static final String VERSION = "text-embedding-3-small@36b5a2d5";
    /** 기록(`ai_version`)에는 manifest 해시만 남는다(추가 검토 2). */
    private static final String LOGGED_VERSION = "@36b5a2d5";
    private static final String SECRET_TEXT = "비밀섹션본문 Sole Proprietorship";

    private final ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockWebServer server;
    private final Rows writer = new Rows();
    private WebClientAiClient client;

    /** 행을 모으고, 저장이 호출 스레드에서 일어나는지 단언한다. */
    private static class Rows extends AiCallLogWriter {
        final List<AiCallLog> rows = new ArrayList<>();
        final Thread caller = Thread.currentThread();

        Rows() { super(null); }

        @Override
        public void write(AiCallLog log) {
            assertThat(Thread.currentThread()).isSameAs(caller);
            rows.add(log);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = newClient(server.url("/").toString(), 5, 5, 5, 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** 기본·질문 답변·참고 사례 검색·승인 STR 색인 한도(초). */
    private WebClientAiClient newClient(String baseUrl, int timeoutSeconds, int askSeconds, int searchSeconds,
                                        int indexSeconds) {
        var props = new AmlProperties(new AmlProperties.Ai(baseUrl, KEY, timeoutSeconds, 500, false,
                askSeconds, false, 5, searchSeconds, false, indexSeconds, false), null, null, null);
        return new WebClientAiClient(WebClient.builder().baseUrl(baseUrl).build(), props, om, writer);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String example(String name) throws Exception {
        return Files.readString(Path.of("contracts/examples/" + name));
    }

    /** 보낸 번호(STR-2026-000012)로 맞춘 응답 예시. */
    private String indexExample(String status) throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(example("str_index_response_" + status + ".json"));
        b.put("report_no", "STR-2026-000012");
        return b.toString();
    }

    private StrIndexRequest req() throws Exception {
        StrIndexRequest base = om.treeToValue(om.readTree(example("str_index_request.json")), StrIndexRequest.class);
        List<StrIndexSection> sections = new ArrayList<>(base.sections());
        sections.set(0, new StrIndexSection("SUBJECT", SECRET_TEXT));   // 로그에 나오면 안 되는 낱말
        return new StrIndexRequest(base.reportNo(), base.draftId(), base.approvedAt(), base.typologies(), sections);
    }

    private static long budgetMs(RecordedRequest req) {
        String v = req.getHeader("X-Budget-Ms");
        assertThat(v).as("X-Budget-Ms").isNotNull().containsOnlyDigits();
        return Long.parseLong(v);
    }

    private static void assertBadResponse(Throwable e) {
        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
        });
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_다섯_칸을_보내고_INDEXED_를_읽으며_초안_번호로_DRAFT_행을_남긴다() throws Exception {
        server.enqueue(json(200, indexExample("indexed")));

        StrIndexResponse r = client.indexApprovedStr(req());

        RecordedRequest sentReq = server.takeRequest();
        assertThat(sentReq.getMethod()).isEqualTo("POST");
        assertThat(sentReq.getPath()).isEqualTo("/v1/references/str");
        assertThat(sentReq.getHeader("X-Internal-Key")).isEqualTo(KEY);
        assertThat(sentReq.getHeader("X-Request-Id")).isNotBlank();
        JsonNode sent = om.readTree(sentReq.getBody().readUtf8());
        assertThat(sent.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("report_no", "draft_id", "approved_at", "typologies", "sections");
        assertThat(sent.get("draft_id").textValue()).isEqualTo("DRF-000312");
        assertThat(sent.get("sections").get(0).fieldNames()).toIterable().containsExactly("section", "text");
        assertThat(sent.get("sections").get(0).get("text").textValue()).isEqualTo(SECRET_TEXT);   // 자르지 않음

        assertThat(r.status()).isEqualTo(StrIndexStatus.INDEXED);
        assertThat(r.skipReason()).isNull();
        assertThat(r.chunkCount()).isEqualTo(4);
        assertThat(r.reportNo()).isEqualTo("STR-2026-000012");
        assertThat(r.raw().path("chunk_count").asInt()).isEqualTo(4);

        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/references/str");
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
        assertThat(row.getTargetId()).isEqualTo("DRF-000312");
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getGenerationId()).isNull();
        assertThat(row.getAiVersion()).isEqualTo(LOGGED_VERSION);
    }

    @Test
    void 정상_SKIPPED_DISABLED_는_버전_null_이어도_읽고_ai_version_은_null() throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(indexExample("skipped"));
        b.put("skip_reason", "DISABLED");
        b.putNull("retrieval_version");
        server.enqueue(json(200, b.toString()));

        StrIndexResponse r = client.indexApprovedStr(req());

        assertThat(r.status()).isEqualTo(StrIndexStatus.SKIPPED);
        assertThat(r.skipReason()).isEqualTo(StrIndexSkipReason.DISABLED);
        assertThat(r.chunkCount()).isZero();
        assertThat(writer.rows.get(0).getAiVersion()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isNull();
    }

    // --- 예산 ---------------------------------------------------------------

    @Test
    void 정상_X_Budget_Ms_는_색인_예산이고_검색_질문_초안은_각자_예산_그대로다() throws Exception {
        client = newClient(server.url("/").toString(), 5, 3, 2, 4);
        server.enqueue(json(200, indexExample("indexed")));
        server.enqueue(json(200, example("reference_search_response_found.json")));
        server.enqueue(json(200, example("ask_response_answered.json")));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.indexApprovedStr(req());
        client.searchReferences("DRF-000312", new ReferenceSearchRequest(List.of("CYCLE"), null, 5, List.of("PUBLIC")));
        client.ask(AskRequest.of(om.treeToValue(om.readTree(example("summary_request.json")), CaseSummaryRequest.class),
                "질문"));
        client.createDraft(new StrDraftRequest("D1", "CASE-1", null, List.of(), null, 0, null, null));

        assertThat(budgetMs(server.takeRequest())).isGreaterThan(3_000).isLessThanOrEqualTo(4_000);   // 색인 4초
        assertThat(budgetMs(server.takeRequest())).isPositive().isLessThanOrEqualTo(2_000);           // 검색 2초
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(2_000).isLessThanOrEqualTo(3_000);   // 질문 3초
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(4_000).isLessThanOrEqualTo(5_000);   // 초안 5초
    }

    @Test
    void 경계_자기_예산으로_끊고_재시도하지_않는다() throws Exception {
        client = newClient(server.url("/").toString(), 5, 5, 5, 1);
        server.enqueue(json(200, indexExample("indexed")).setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.indexApprovedStr(req()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_TIMEOUT");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");
    }

    // --- 재시도 ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"502, LLM_UNAVAILABLE", "503, DB_UNAVAILABLE", "503, MODEL_UNAVAILABLE"})
    void 경계_재시도_대상은_1회_재시도하고_같은_요청id_줄어든_예산으로_두_행이다(int status, String code) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\"}").setBodyDelay(50, TimeUnit.MILLISECONDS));
        server.enqueue(json(200, indexExample("indexed")));

        assertThat(client.indexApprovedStr(req()).status()).isEqualTo(StrIndexStatus.INDEXED);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(code, null);
        assertThat(writer.rows).extracting(AiCallLog::getEndpoint).containsOnly("/v1/references/str");
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(second.getHeader("X-Request-Id")).isEqualTo(first.getHeader("X-Request-Id"));
        assertThat(budgetMs(second)).isPositive().isLessThan(budgetMs(first));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({"400, INVALID_REQUEST, AI_INVALID_REQUEST", "502, LLM_BUDGET_EXCEEDED, LLM_BUDGET_EXCEEDED",
            "500, INTERNAL, INTERNAL", "401, UNAUTHENTICATED, AI_UNAUTHENTICATED"})
    void 재시도없음_400_일_한도_그밖은_재시도하지_않고_502(int status, String code, String want) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"" + SECRET_TEXT + "\"}"));

        Throwable e = catchThrowable(() -> client.indexApprovedStr(req()));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo(want);
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).doesNotContain("비밀섹션본문");   // AI 문장 대신 고정 문구
        });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(status);
    }

    @Test
    void 경계_연결_거부는_재시도하고_AI_UNAVAILABLE_두_행이다() throws Exception {
        int port = server.getPort();
        server.shutdown();
        WebClientAiClient c = newClient("http://localhost:" + port, 5, 5, 5, 2);
        server = new MockWebServer();   // tearDown 용

        assertThatThrownBy(() -> c.indexApprovedStr(req()))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getTargetId).containsOnly("DRF-000312");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"500, RETRIEVAL_UNAVAILABLE", "409, GENERATION_IN_PROGRESS", "502, 비밀섹션본문"})
    void 실패_색인이_낼_수_없는_AI_코드는_AI_BAD_RESPONSE_이고_문구는_고정이다(int status, String code) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"" + SECRET_TEXT + "\"}"));

        Throwable e = catchThrowable(() -> client.indexApprovedStr(req()));

        assertBadResponse(e);
        assertThat(e.getMessage()).doesNotContain("비밀섹션본문").doesNotContain(code);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    // --- 응답 확인 실패 · 기록 칸 ------------------------------------------------

    @Test
    void 실패_2xx_응답_확인이_틀리면_실패_행이고_재시도_없다() throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(indexExample("indexed"));
        b.put("report_no", "STR-2026-000099");   // 보낸 번호와 다름
        server.enqueue(json(200, b.toString()));

        assertBadResponse(catchThrowable(() -> client.indexApprovedStr(req())));

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(row.getAiVersion()).isEqualTo(LOGGED_VERSION);
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
        assertThat(row.getEndpoint()).isEqualTo("/v1/references/str");
    }

    @Test
    void 경계_retrieval_version_길이와_상관없이_ai_version_은_해시만() throws Exception {
        ObjectNode longVersion = (ObjectNode) om.readTree(indexExample("indexed"));
        longVersion.put("retrieval_version", "m".repeat(56) + "@36b5a2d5");   // 65자
        ObjectNode exact = (ObjectNode) om.readTree(indexExample("indexed"));
        exact.put("retrieval_version", "m".repeat(55) + "@36b5a2d5");     // 64자
        server.enqueue(json(200, longVersion.toString()));
        server.enqueue(json(200, exact.toString()));

        client.indexApprovedStr(req());
        client.indexApprovedStr(req());

        assertThat(writer.rows).extracting(AiCallLog::getAiVersion).containsExactly(LOGGED_VERSION, LOGGED_VERSION);
    }

    // --- 기록 버전 모양 · 뒤에 붙은 JSON (추가 검토 1 — 검색과 같은 공통 메서드) ----------------------------------

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(value = {"CUSTOMER-50438@36b5a2d5|@36b5a2d5", "/srv/AI Models/bge-m3@36b5a2d5|@36b5a2d5",
            "a@b@36b5a2d5|@36b5a2d5", "보고 대상자는 Sole Proprietorship|NULL", "text-embedding-3-small|NULL",
            "text-embedding-3-small@36b5a2d|NULL", "text-embedding-3-small@36B5A2D5|NULL",
            "'text-embedding-3-small@36b5a2d5 '|NULL", "@36b5a2d5x|NULL"}, delimiter = '|', nullValues = "NULL")
    void 검토2_H1_ai_version은_마지막_골뱅이_뒤_해시만이고_응답은_그대로다(String version, String logged) throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(indexExample("indexed"));
        b.put("retrieval_version", version);
        server.enqueue(json(200, b.toString()));

        assertThat(client.indexApprovedStr(req()).retrievalVersion()).isEqualTo(version);   // DTO 는 받은 그대로
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo(logged);
    }

    @Test
    void 검토1_H1_2xx_확인_실패_행도_해시만_남는다() throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(indexExample("indexed"));
        b.put("report_no", "STR-2026-000099");   // 확인 실패
        b.put("retrieval_version", "CUSTOMER-50438@36b5a2d5");   // 앞부분이 섹션 글이어도
        server.enqueue(json(200, b.toString()));

        assertBadResponse(catchThrowable(() -> client.indexApprovedStr(req())));
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo(LOGGED_VERSION);   // 해시만
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @Test
    void 검토1_M4_2xx_본문_뒤에_JSON이_더_붙으면_재시도_없이_AI_BAD_RESPONSE_한_행이다() throws Exception {
        server.enqueue(json(200, indexExample("indexed") + indexExample("skipped")));

        assertBadResponse(catchThrowable(() -> client.indexApprovedStr(req())));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(200);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"status", "chunk_count", "skip_reason"})
    void 검토2_M2_2xx_본문에_같은_칸이_두_번이면_순서와_상관없이_AI_BAD_RESPONSE_한_행이다(String field) throws Exception {
        String body = indexExample("indexed");
        String dup = switch (field) {
            case "status" -> "\"status\":\"SKIPPED\"";
            case "chunk_count" -> "\"chunk_count\":0";
            default -> "\"skip_reason\":\"RAW_ACCOUNT\"";
        };
        server.enqueue(json(200, body.replaceFirst("\\{", "{" + dup + ",")));        // 앞에 겹친 칸
        server.enqueue(json(200, body.replaceFirst("\\}\\s*$", "," + dup + "}")));  // 뒤에 겹친 칸

        assertBadResponse(catchThrowable(() -> client.indexApprovedStr(req())));
        assertBadResponse(catchThrowable(() -> client.indexApprovedStr(req())));
        assertThat(server.getRequestCount()).isEqualTo(2);   // 재시도 없음
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(200, 200);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_BAD_RESPONSE");
    }

    @Test
    void 검토2_M2_JSON_뒤_공백_줄바꿈은_그대로_성공이다() throws Exception {
        server.enqueue(json(200, indexExample("indexed") + " \n\t\r\n"));
        assertThat(client.indexApprovedStr(req()).status()).isEqualTo(StrIndexStatus.INDEXED);
    }

    // --- 로그 -----------------------------------------------------------------

    private static final List<String> BODY_LOGGERS = List.of("org.springframework.http.codec",
            "org.springframework.core.codec", "org.springframework.web.HttpLogging",
            "org.springframework.web.reactive.function.client", "reactor.netty");

    /** 코덱·HTTP 로거를 TRACE 로 켜고 돌린 뒤 원래 수준으로 되돌린다(309 테스트와 같은 방식). */
    private static void withTraceLogging(ThrowingRunnable run) throws Exception {
        Map<String, Level> before = new LinkedHashMap<>();
        for (String name : BODY_LOGGERS) {
            ch.qos.logback.classic.Logger l = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            before.put(name, l.getLevel());
            l.setLevel(Level.TRACE);
        }
        try {
            run.run();
        } finally {
            before.forEach((name, level) -> ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name)).setLevel(level));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 실패_TRACE_로그를_켜도_성공_실패_경로_모두_섹션_글이_안_남는다(CapturedOutput out) throws Exception {
        ObjectNode bad = (ObjectNode) om.readTree(indexExample("indexed"));
        bad.put("chunk_count", 9);
        server.enqueue(json(200, indexExample("indexed")));
        server.enqueue(json(200, bad.toString()));
        server.enqueue(json(500, "{\"code\":\"INTERNAL\",\"message\":\"" + SECRET_TEXT + "\"}"));

        withTraceLogging(() -> {
            client.indexApprovedStr(req());
            catchThrowable(() -> client.indexApprovedStr(req()));
            catchThrowable(() -> client.indexApprovedStr(req()));
        });

        assertThat(out.getAll()).contains("/v1/references/str")
                .doesNotContain("비밀섹션본문").doesNotContain("Sole Proprietorship").doesNotContain("ACH 입금");
    }
}
