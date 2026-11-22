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
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-309 — `WebClientAiClient.searchReferences` (규약 5.5·6.2). 검색만 자기 호출·재시도 루프를 쓴다:
 * 바이트 송수신(코덱 본문 로그 없음) · 연결 뒤 `X-Budget-Ms`(1ms 미만이면 안 보냄) · AI 오류 코드·문구 거르기.
 * 시도 기록은 다섯 호출과 같이 호출 스레드에서 — 기록 도우미가 `WebClientAiClientTest.RecordingWriter` 처럼 단언한다.
 */
class WebClientReferenceSearchTest {

    private static final String KEY = "test-internal-key-0123456789abcd";
    private static final String SEARCH_QUERY = "  해외 계좌 비밀검색어 흐름 ";
    private static final String TITLE_WORD = "Laundering";   // 예시 문서 제목에 든 낱말
    private static final String VERSION = "text-embedding-3-small@36b5a2d5";
    /** 기록(`ai_version`)에는 manifest 해시만 남는다(SCRUM-357 추가 검토 2). */
    private static final String LOGGED_VERSION = "@36b5a2d5";

    private final ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockWebServer server;
    private final ThreadWriter writer = new ThreadWriter();
    private WebClientAiClient client;

    /** 행을 모으고, 저장이 호출 스레드에서 일어나는지 단언한다(`WebClientAiClientTest.RecordingWriter` 와 같은 방식). */
    private static class ThreadWriter extends AiCallLogWriter {
        final List<AiCallLog> rows = new ArrayList<>();
        final Thread caller = Thread.currentThread();
        /** 첫 행 저장만 이만큼 늦춘다(감사 DB 가 밀린 모양). */
        long firstWriteDelayMs;

        ThreadWriter() { super(null); }

        @Override
        public void write(AiCallLog log) {
            assertThat(Thread.currentThread()).isSameAs(caller);
            if (rows.isEmpty() && firstWriteDelayMs > 0) {
                try {
                    Thread.sleep(firstWriteDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            rows.add(log);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = newClient(5, 5, 5, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** 기본·질문 답변·참고 사례 검색 한도(초), 커넥터(null 이면 기본). */
    private WebClientAiClient newClient(int timeoutSeconds, int askSeconds, int searchSeconds, ClientHttpConnector connector) {
        WebClient.Builder b = WebClient.builder().baseUrl(server.url("/").toString());
        if (connector != null) b.clientConnector(connector);
        var props = new AmlProperties(new AmlProperties.Ai(server.url("/").toString(), KEY, timeoutSeconds, 500, false,
                askSeconds, false, 5, searchSeconds, false), null, null, null);
        return new WebClientAiClient(b.build(), props, om, writer);
    }

    /** 연결 전에 `delayMs` 쉬는 커넥터 — 풀 대기·연결 수립이 늦은 모양. */
    private static ClientHttpConnector slowConnector(long delayMs) {
        ClientHttpConnector real = new ReactorClientHttpConnector();
        return (method, uri, callback) -> Mono.delay(Duration.ofMillis(delayMs)).then(real.connect(method, uri, callback));
    }

    /**
     * `fromConnect` 번째 연결부터, 연결을 잡은 뒤 요청을 쓰기 직전에 `sleepMs` 쉬는 커넥터 — 연결 직후 예산이 다 된 모양.
     * (요청 콜백 안에서 쉬므로 `X-Budget-Ms` 계산이 그 뒤에 일어난다.)
     */
    private static ClientHttpConnector expiresAfterConnect(int fromConnect, long sleepMs) {
        ClientHttpConnector real = new ReactorClientHttpConnector();
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return (method, uri, callback) -> {
            boolean slow = n.incrementAndGet() >= fromConnect;
            return real.connect(method, uri, req -> {
                if (slow) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return callback.apply(req);
            });
        };
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String example(String name) throws Exception {
        return Files.readString(Path.of("contracts/examples/" + name));
    }

    private static String searchExample(String status) throws Exception {
        return example("reference_search_response_" + status + ".json");
    }

    private static ReferenceSearchRequest searchReq(String query) {
        return new ReferenceSearchRequest(List.of("CYCLE"), query, 5, List.of("PUBLIC"));
    }

    private AskRequest askReq(String question) throws Exception {
        return AskRequest.of(om.treeToValue(om.readTree(example("summary_request.json")), CaseSummaryRequest.class), question);
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
    void 정상_규약_5_5_네_칸만_보내고_검색어를_그대로_보내며_DRAFT_행을_기록_스레드에서_남긴다() throws Exception {
        server.enqueue(json(200, searchExample("found")));

        ReferenceSearchResponse r = client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/references/search");
        assertThat(req.getHeader("X-Internal-Key")).isEqualTo(KEY);
        assertThat(req.getHeader("Content-Type")).startsWith("application/json");
        JsonNode sent = om.readTree(req.getBody().readUtf8());
        assertThat(sent.fieldNames()).toIterable().containsExactlyInAnyOrder("typologies", "query", "top_k", "source_types");
        assertThat(sent.get("query").textValue()).isEqualTo(SEARCH_QUERY);   // 자르지 않음
        assertThat(sent.toString()).doesNotContain("DRF-000312");           // 초안 번호는 본문에 없음
        assertThat(r.status()).isEqualTo(ReferenceSearchStatus.FOUND);
        assertThat(r.results()).hasSize(2);
        assertThat(r.raw().path("search_meta").path("candidates").asInt()).isEqualTo(12);

        assertThat(writer.rows).hasSize(1);   // 남은 예산 안에서 기다려 줌
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/references/search");
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
        assertThat(row.getTargetId()).isEqualTo("DRF-000312");
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getGenerationId()).isNull();
        assertThat(row.getAiVersion()).isEqualTo(LOGGED_VERSION);
        assertThat(row.getDurationMs()).isBetween(0, 2_000);
    }

    @Test
    void 정상_검색어_null은_null로_나가고_EMPTY도_읽는다() throws Exception {
        server.enqueue(json(200, searchExample("empty")));

        ReferenceSearchResponse r = client.searchReferences("DRF-000312", searchReq(null));

        JsonNode sent = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.get("query").isNull()).isTrue();
        assertThat(r.status()).isEqualTo(ReferenceSearchStatus.EMPTY);
        assertThat(r.results()).isEmpty();
    }

    // --- 예산 · X-Budget-Ms (M3) ------------------------------------------------

    @Test
    void 경계_자기_예산으로_끊고_같은_지연에도_요약은_기본_예산이라_성공한다() throws Exception {
        client = newClient(5, 5, 1, null);   // 기본 5초 · 검색 1초
        server.enqueue(json(200, searchExample("found")).setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.searchReferences("DRF-000312", searchReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_TIMEOUT");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);   // 타임아웃은 재시도 안 함
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");

        server.enqueue(json(200, "{\"case_no\":\"CASE-2022-000001\",\"generation_meta\":{\"prompt_version\":\"p-1\"}}")
                .setBodyDelay(2, TimeUnit.SECONDS));
        assertThat(client.caseSummary(new CaseSummaryRequest("CASE-2022-000001", null, List.of(), null, 0, null))
                .caseNo()).isEqualTo("CASE-2022-000001");
    }

    @Test
    void 정상_X_Budget_Ms는_검색_예산_기준이고_질문_답변_초안은_각자_예산_그대로다() throws Exception {
        client = newClient(5, 3, 2, null);
        server.enqueue(json(200, searchExample("found")));
        server.enqueue(json(200, example("ask_response_answered.json")));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.searchReferences("DRF-000312", searchReq("q"));
        client.ask(askReq("q"));
        client.createDraft(new StrDraftRequest("D1", "CASE-1", null, List.of(), null, 0, null, null));

        assertThat(budgetMs(server.takeRequest())).isPositive().isLessThanOrEqualTo(2_000);
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(2_000).isLessThanOrEqualTo(3_000);
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(3_000).isLessThanOrEqualTo(5_000);
    }

    @Test
    void 경계_X_Budget_Ms는_연결을_잡은_뒤_계산해_연결_대기를_뺀다_재시도도_같다() throws Exception {
        client = newClient(5, 5, 2, slowConnector(500));
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}"));
        server.enqueue(json(200, searchExample("found")));

        assertThat(client.searchReferences("DRF-000312", searchReq("q")).status()).isEqualTo(ReferenceSearchStatus.FOUND);

        long first = budgetMs(server.takeRequest());
        long second = budgetMs(server.takeRequest());
        assertThat(first).isPositive().isLessThanOrEqualTo(1_520);           // 2000 − 연결 대기 500
        assertThat(second).isPositive().isLessThanOrEqualTo(first - 480);    // 재시도도 연결 대기만큼 더 줄어듦
    }

    // --- 재시도 ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"502, LLM_UNAVAILABLE", "503, DB_UNAVAILABLE", "503, MODEL_UNAVAILABLE"})
    void 경계_재시도_대상_코드는_1회_재시도하고_같은_요청id_줄어든_예산으로_두_행이다(int status, String code) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\"}").setBodyDelay(50, TimeUnit.MILLISECONDS));
        server.enqueue(json(200, searchExample("found")));

        assertThat(client.searchReferences("DRF-000312", searchReq("q")).status()).isEqualTo(ReferenceSearchStatus.FOUND);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(code, null);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(status, 200);
        assertThat(writer.rows).extracting(AiCallLog::getTargetId).containsOnly("DRF-000312");
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(second.getHeader("X-Request-Id")).isEqualTo(first.getHeader("X-Request-Id"));
        assertThat(budgetMs(second)).isPositive().isLessThan(budgetMs(first));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({"502, LLM_BUDGET_EXCEEDED, LLM_BUDGET_EXCEEDED", "400, INVALID_REQUEST, AI_INVALID_REQUEST",
            "500, INTERNAL, INTERNAL", "400, LLM_UNAVAILABLE, LLM_UNAVAILABLE"})
    void 재시도없음_일_한도_400_그밖_5xx_4xx는_재시도하지_않고_502(int status, String code, String want) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\"}"));

        assertThatThrownBy(() -> client.searchReferences("DRF-000312", searchReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo(want);
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(status);
    }

    @Test
    void 경계_연결_거부는_재시도하고_AI_UNAVAILABLE_두_행이다() throws Exception {
        int port = server.getPort();
        server.shutdown();   // 같은 포트가 닫혀 연결 거부
        var props = new AmlProperties(new AmlProperties.Ai("http://localhost:" + port, KEY, 5, 500, false, 5, false, 5, 2, false),
                null, null, null);
        WebClientAiClient c = new WebClientAiClient(WebClient.builder().baseUrl("http://localhost:" + port).build(),
                props, om, writer);
        server = new MockWebServer();   // tearDown 용

        assertThatThrownBy(() -> c.searchReferences("DRF-000312", searchReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsOnlyNulls();
    }

    // --- 응답 확인 실패 · 기록 칸 (M4) ------------------------------------------

    @Test
    void 실패_2xx_응답_확인이_틀리면_실패_행이고_재시도_없다() throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(searchExample("found"));
        ((ObjectNode) body.get("results").get(0)).put("source_type", "APPROVED_STR");   // 보낸 출처 밖
        server.enqueue(json(200, body.toString()));

        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(row.getAiVersion()).isEqualTo(LOGGED_VERSION);   // 본문 메타는 남음(해시만)
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
    }

    @Test
    void 실패_2xx_본문이_JSON이_아니거나_비면_실패_행이고_재시도_없다() throws Exception {
        server.enqueue(json(200, "not json"));
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"));

        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(200, 200);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_BAD_RESPONSE");
    }

    @Test
    void M4_응답에_계약_밖_generation_id가_있어도_성공_실패_행_모두_null이다() throws Exception {
        ObjectNode ok = (ObjectNode) om.readTree(searchExample("found"));
        ok.put("generation_id", "Updated indicators: Laundering the proceeds of crime through underground banking schemes");
        ObjectNode bad = ok.deepCopy();
        bad.putArray("results");   // FOUND 인데 0건 → 확인 실패
        server.enqueue(json(200, ok.toString()));
        server.enqueue(json(200, bad.toString()));

        client.searchReferences("DRF-000312", searchReq("q"));
        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));

        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(null, "AI_BAD_RESPONSE");
        assertThat(writer.rows).extracting(AiCallLog::getGenerationId).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getAiVersion).containsOnly(LOGGED_VERSION);
    }

    @Test
    void 경계_retrieval_version이_문자열이_아니면_null이고_길이와_상관없이_해시만_남는다() throws Exception {
        ObjectNode longVersion = (ObjectNode) om.readTree(searchExample("empty"));
        longVersion.put("retrieval_version", "m".repeat(56) + "@36b5a2d5");   // 65자
        ObjectNode numberVersion = (ObjectNode) om.readTree(searchExample("empty"));
        numberVersion.put("retrieval_version", 3);   // 응답 확인도 실패
        ObjectNode exact = (ObjectNode) om.readTree(searchExample("empty"));
        exact.put("retrieval_version", "m".repeat(55) + "@36b5a2d5");     // 64자 — 모양이 맞아야 남음(357 검토 1)
        server.enqueue(json(200, longVersion.toString()));
        server.enqueue(json(200, numberVersion.toString()));
        server.enqueue(json(200, exact.toString()));

        client.searchReferences("DRF-000312", searchReq("q"));
        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        client.searchReferences("DRF-000312", searchReq("q"));

        // 357 추가 검토 2 — 길이와 상관없이 마지막 @ 뒤 해시만 남음, 문자열이 아니면 null
        assertThat(writer.rows).extracting(AiCallLog::getAiVersion).containsExactly(LOGGED_VERSION, null, LOGGED_VERSION);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(null, "AI_BAD_RESPONSE", null);
    }

    // --- 기록 버전 모양 · 뒤에 붙은 JSON (SCRUM-357 추가 검토 1 — 색인과 같은 공통 메서드) ------------------------

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(value = {"CUSTOMER-50438@36b5a2d5|@36b5a2d5", "/srv/AI Models/bge-m3@36b5a2d5|@36b5a2d5",
            "a@b@36b5a2d5|@36b5a2d5", "보고 대상자는 Sole Proprietorship|NULL", "' '|NULL", "text-embedding-3-small|NULL",
            "text-embedding-3-small@36b5a2d|NULL", "text-embedding-3-small@36B5A2D5|NULL",
            "'text-embedding-3-small@36b5a2d5 '|NULL", "@36b5a2d5x|NULL"}, delimiter = '|', nullValues = "NULL")
    void 검토357_H1_ai_version은_마지막_골뱅이_뒤_해시만이고_응답은_그대로다(String version, String logged) throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(searchExample("found"));
        b.put("retrieval_version", version);
        server.enqueue(json(200, b.toString()));

        // 응답 확인은 그대로(검색은 문자열 1자 이상만 봄) — DTO 값은 받은 그대로, 기록에만 해시
        assertThat(client.searchReferences("DRF-000312", searchReq("q")).retrievalVersion()).isEqualTo(version);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo(logged);
    }

    @Test
    void 검토357_H1_2xx_확인_실패_행도_해시만_남는다() throws Exception {
        ObjectNode b = (ObjectNode) om.readTree(searchExample("found"));
        ((ObjectNode) b.get("results").get(0)).put("source_type", "APPROVED_STR");   // 확인 실패
        b.put("retrieval_version", "CUSTOMER-50438@36b5a2d5");   // 앞부분이 섹션 글이어도
        server.enqueue(json(200, b.toString()));

        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo(LOGGED_VERSION);   // 해시만
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @Test
    void 검토357_M4_2xx_본문_뒤에_JSON이_더_붙으면_재시도_없이_AI_BAD_RESPONSE_한_행이다() throws Exception {
        server.enqueue(json(200, searchExample("found") + searchExample("empty")));

        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(200);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"status", "results", "retrieval_version"})
    void 검토357_M2_2xx_본문에_같은_칸이_두_번이면_순서와_상관없이_AI_BAD_RESPONSE_한_행이다(String field) throws Exception {
        String body = searchExample("found");
        String dup = switch (field) {
            case "status" -> "\"status\":\"EMPTY\",";
            case "results" -> "\"results\":[],";
            default -> "\"retrieval_version\":\"x@00000000\",";
        };
        server.enqueue(json(200, body.replaceFirst("\\{", "{" + dup)));                       // 앞에 겹친 칸
        server.enqueue(json(200, body.replaceFirst("\\}\\s*$", "," + dup.replaceAll(",$", "") + "}")));   // 뒤에 겹친 칸

        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        assertBadResponse(catchThrowable(() -> client.searchReferences("DRF-000312", searchReq("q"))));
        assertThat(server.getRequestCount()).isEqualTo(2);   // 재시도 없음(호출 두 번에 요청 두 번)
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(200, 200);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_BAD_RESPONSE");
    }

    @Test
    void 검토357_M2_JSON_뒤_공백_줄바꿈은_그대로_성공이다() throws Exception {
        server.enqueue(json(200, searchExample("found") + " \n\t\r\n"));
        assertThat(client.searchReferences("DRF-000312", searchReq("q")).status()).isEqualTo(ReferenceSearchStatus.FOUND);
    }

    // --- 로그 (H1) ---------------------------------------------------------------

    private static final List<String> BODY_LOGGERS = List.of("org.springframework.http.codec",
            "org.springframework.core.codec", "org.springframework.web.HttpLogging",
            "org.springframework.web.reactive.function.client", "org.springframework.web.servlet", "reactor.netty");

    /** 코덱·HTTP 로거를 TRACE 로 켜고 돌린 뒤 원래 수준으로 되돌린다. */
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
    void H1_코덱_HTTP_로그를_TRACE로_켜도_검색어와_문서_제목이_안_남는다_대조로_질문_답변은_남는다(CapturedOutput out)
            throws Exception {
        String question = "대조용비밀질문문장";
        server.enqueue(json(200, searchExample("found")));
        server.enqueue(json(200, example("ask_response_answered.json")));

        withTraceLogging(() -> {
            client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY));
            int searchEnd = out.getAll().length();
            client.ask(askReq(question));   // 기존 경로(코덱)는 그대로 — 이 테스트가 TRACE 를 실제로 켰는지 대조
            String searchPart = out.getAll().substring(0, searchEnd);
            assertThat(searchPart).contains("/v1/references/search")
                    .doesNotContain("비밀검색어").doesNotContain(TITLE_WORD).doesNotContain("fintrac-underground");
            assertThat(out.getAll().substring(searchEnd)).contains(question);
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void H1_실패_경로_로그에도_검색어와_문서_제목이_없다(CapturedOutput out) throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(searchExample("found"));
        ((ObjectNode) body.get("results").get(0)).put("source_type", "APPROVED_STR");
        server.enqueue(json(200, body.toString()));
        server.enqueue(json(500, "{\"code\":\"INTERNAL\",\"message\":\"Laundering 비밀검색어\"}"));

        withTraceLogging(() -> {
            catchThrowable(() -> client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY)));
            catchThrowable(() -> client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY)));
        });

        assertThat(out.getAll()).contains("/v1/references/search").doesNotContain("비밀검색어").doesNotContain(TITLE_WORD);
    }

    // --- 기록 (검토 2 — 호출 스레드로 되돌림) ---------------------------------------

    @Test
    void 정상_감사_저장은_호출_스레드에서_시도마다_한_번씩_순서대로_남는다() throws Exception {
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\"}"));
        server.enqueue(json(200, searchExample("found")));

        client.searchReferences("DRF-000312", searchReq("q"));   // ThreadWriter 가 호출 스레드 단언

        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("DB_UNAVAILABLE", null);
        assertThat(writer.rows.get(0).getCreatedAt()).isBeforeOrEqualTo(writer.rows.get(1).getCreatedAt());
    }

    // --- AI 오류 본문 거르기 (검토 2 H1) ---------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"500, 비밀검색어_Laundering", "502, RETRIEVAL_UNAVAILABLE", "409, GENERATION_IN_PROGRESS"})
    void 검토2_H1_검색이_낼_수_없는_AI_코드는_AI_BAD_RESPONSE이고_문구는_고정이다(int status, String code) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"비밀검색어 Laundering\"}"));

        Throwable e = catchThrowable(() -> client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY)));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).isEqualTo("AI 응답 처리 실패");
        });
        assertThat(server.getRequestCount()).isEqualTo(1);   // 재시도 없음
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({"400, INVALID_REQUEST, AI_INVALID_REQUEST", "401, UNAUTHENTICATED, AI_UNAUTHENTICATED",
            "502, LLM_BUDGET_EXCEEDED, LLM_BUDGET_EXCEEDED", "500, INTERNAL, INTERNAL"})
    void 검토2_H1_아는_코드는_그대로지만_AI_문장_대신_고정_문구다(int status, String code, String want) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"비밀검색어 Laundering\"}"));

        Throwable e = catchThrowable(() -> client.searchReferences("DRF-000312", searchReq(SEARCH_QUERY)));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo(want);
            assertThat(ae.getMessage()).doesNotContain("비밀검색어").doesNotContain(TITLE_WORD).isNotBlank();
        });
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo(want);
    }

    @RestController
    static class SearchController {
        private final AiClient ai;
        SearchController(AiClient ai) { this.ai = ai; }

        @PostMapping("/test/search")
        Object search() {
            return ai.searchReferences("DRF-000312", searchReq(SEARCH_QUERY));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 검토2_H1_화면_오류_응답과_MVC_TRACE_로그_감사_행에_AI_오류_문장이_없다(CapturedOutput out) throws Exception {
        server.enqueue(json(500, "{\"code\":\"비밀검색어 Laundering\",\"message\":\"비밀검색어 Laundering\"}"));
        server.enqueue(json(400, "{\"code\":\"INVALID_REQUEST\",\"message\":\"비밀검색어 Laundering\"}"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SearchController(client))
                .setControllerAdvice(new com.team.aml.shared.api.GlobalExceptionHandler(
                        new com.team.aml.shared.metrics.ErrorMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())))
                .build();

        List<String> bodies = new ArrayList<>();
        withTraceLogging(() -> {
            for (int i = 0; i < 2; i++) {
                bodies.add(mvc.perform(post("/test/search")).andExpect(status().isBadGateway())
                        .andReturn().getResponse().getContentAsString());
            }
        });

        assertThat(bodies.get(0)).contains("AI_BAD_RESPONSE");
        assertThat(bodies.get(1)).contains("AI_INVALID_REQUEST");
        assertThat(String.join("", bodies)).doesNotContain("비밀검색어").doesNotContain(TITLE_WORD);
        assertThat(out.getAll()).doesNotContain("비밀검색어").doesNotContain(TITLE_WORD);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("AI_BAD_RESPONSE", "AI_INVALID_REQUEST");
    }

    // --- 연결 직후 예산 소진 (검토 2 M4) ----------------------------------------------

    @Test
    void 검토2_M4_보낼_예산은_1ms부터이고_1ms_미만이면_재시도_없는_AI_TIMEOUT으로_분류된다() {
        long ms = 1_000_000L;
        assertThat(WebClientAiClient.searchBudgetMs(10 * ms, 9 * ms)).isEqualTo(1);
        assertThat(WebClientAiClient.searchBudgetMs(10_000 * ms, 0)).isEqualTo(10_000);
        for (long now : new long[]{10 * ms - 1, 10 * ms, 11 * ms}) {   // 0.999ms · 0 · 음수
            Throwable e = catchThrowable(() -> WebClientAiClient.searchBudgetMs(10 * ms, now));
            WebClientAiClient.Classified c = client.classify(e);
            assertThat(c.ex().code()).isEqualTo("AI_TIMEOUT");
            assertThat(c.retryable()).isFalse();
        }
    }

    @Test
    void 검토2_M4_연결을_잡은_뒤_예산이_다_됐으면_요청을_안_보내고_AI_TIMEOUT_한_행이다() throws Exception {
        client = newClient(5, 5, 1, expiresAfterConnect(1, 1_200));
        server.enqueue(json(200, searchExample("found")));

        assertThatThrownBy(() -> client.searchReferences("DRF-000312", searchReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_TIMEOUT"));

        Thread.sleep(300);   // 늦게 쓰는 요청이 있으면 서버에 닿을 시간
        assertThat(server.getRequestCount()).isZero();   // X-Budget-Ms: 0 요청이 서버에 안 감
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
    }

    @Test
    void 검토2_M4_재시도_때_연결_뒤_예산이_다_됐으면_두번째_요청을_안_보낸다() throws Exception {
        client = newClient(5, 5, 1, expiresAfterConnect(2, 1_200));
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}"));
        server.enqueue(json(200, searchExample("found")));

        assertThatThrownBy(() -> client.searchReferences("DRF-000312", searchReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_TIMEOUT"));

        Thread.sleep(300);
        assertThat(server.getRequestCount()).isEqualTo(1);   // 첫 시도만
        assertThat(budgetMs(server.takeRequest())).isPositive();
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("LLM_UNAVAILABLE", "AI_TIMEOUT");
    }

    // --- 재시도 전 감사 저장 지연 (검토 3 H1) ------------------------------------------

    @Test
    void 검토3_H1_첫_실패_뒤_감사_저장이_예산보다_길어도_남은_예산으로_재시도하고_저장_시간은_안_섞인다() throws Exception {
        client = newClient(5, 5, 2, null);   // 검색 예산 2초
        writer.firstWriteDelayMs = 2_500;    // 첫 행 저장만 2.5초 — 예산보다 김
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\"}"));
        server.enqueue(json(200, searchExample("empty")));

        ReferenceSearchResponse r = client.searchReferences("DRF-000312", searchReq("q"));

        assertThat(r.status()).isEqualTo(ReferenceSearchStatus.EMPTY);
        assertThat(server.getRequestCount()).isEqualTo(2);   // 두 번째 요청이 감
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("DB_UNAVAILABLE", null);
        assertThat(writer.rows).allSatisfy(row -> assertThat(row.getDurationMs()).isLessThan(1_000));   // 저장 지연 없음
        long first = budgetMs(server.takeRequest());
        long second = budgetMs(server.takeRequest());
        assertThat(first).isPositive().isLessThanOrEqualTo(2_000);
        assertThat(second).isPositive().isLessThanOrEqualTo(first);   // 확정한 남은 예산 이하
        assertThat(second).isGreaterThan(1_000);                        // 저장 2.5초가 빠짐(안 빠지면 0 이하라 못 보냄)
    }
}
