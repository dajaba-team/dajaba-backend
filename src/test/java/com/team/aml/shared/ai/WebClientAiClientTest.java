package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import com.team.aml.shared.config.AmlProperties;
import io.netty.channel.ConnectTimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WebClientAiClient — 규약 §6 공통 동작을 MockWebServer 로 고정 (SCRUM-17).
 * 재시도는 HTTP 5xx 가 아니라 코드/원인으로, 연결 거부↔연결 타임아웃 구분, 409 → DRAFT_IN_PROGRESS.
 */
class WebClientAiClientTest {

    private final ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockWebServer server;
    private WebClientAiClient client;
    private final RecordingWriter writer = new RecordingWriter();

    private static class RecordingWriter extends AiCallLogWriter {
        final List<AiCallLog> rows = new ArrayList<>();
        boolean fail;
        final Thread caller = Thread.currentThread();

        RecordingWriter() { super(null); }

        @Override
        public void write(AiCallLog log) {
            assertThat(Thread.currentThread()).isSameAs(caller);
            rows.add(log);
            if (fail) throw new RuntimeException("기록 실패");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = newClient(server.url("/").toString(), 5);
    }

    @AfterEach
    void tearDown() {
        try {
            server.shutdown();
        } catch (Exception ignore) {
            // 연결 거부 테스트에서 이미 내렸을 수 있다
        }
    }

    private WebClientAiClient newClient(String baseUrl, int timeoutSeconds) {
        var wc = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(baseUrl).build();
        var props = new AmlProperties(
                new AmlProperties.Ai(baseUrl, "test-internal-key-0123456789abcd", timeoutSeconds, 500, false),
                null, null, null);
        return new WebClientAiClient(wc, props, om, writer);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private AlertPayload alert(String id) {
        return new AlertPayload(id, "010_ACCT", "ENT-1", "R02_FAN_IN_24H", "1", "FAN_IN",
                "2022-09-02T00:00:00", "2022-09-02T23:59:59", "2022-09-02T23:59:59", List.of(1L, 2L), null,
                "2026-11-03T01:00:00Z");
    }

    private StrDraftRequest draftReq() {
        return new StrDraftRequest("DRF-000312", "CASE-2022-000057", alert("ALT-1"),
                List.of(1L, 2L), null, 0, "조사 메모", "Bank");
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_createDraft_200을_매핑하고_내부키와_요청id_헤더를_보낸다() throws Exception {
        server.enqueue(json(200, "{\"draft_id\":\"DRF-000312\",\"generation_id\":\"g-7c1d44e2\",\"facts\":{},\"narrative\":[]}"));

        StrDraftResponse res = client.createDraft(draftReq());

        assertThat(res.draftId()).isEqualTo("DRF-000312");
        assertThat(res.generationId()).isEqualTo("g-7c1d44e2");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/str/drafts");
        assertThat(req.getHeader("X-Internal-Key")).isEqualTo("test-internal-key-0123456789abcd");
        assertThat(req.getHeader("X-Request-Id")).isNotBlank();
    }

    private static String example(String name) throws Exception {
        return Files.readString(Path.of("contracts/examples", name));
    }

    @Test
    void 정상_초안_요청_본문이_계약_예시와_같고_응답_칸을_읽는다() throws Exception {
        server.enqueue(json(200, example("draft_response.json")));
        StrDraftRequest request = om.readValue(example("draft_request.json"), StrDraftRequest.class);

        StrDraftResponse res = client.createDraft(request);

        assertThat(om.readTree(server.takeRequest().getBody().readUtf8()))
                .isEqualTo(om.readTree(example("draft_request.json")));
        assertThat(res.draftId()).isEqualTo("DRF-000312");
        assertThat(res.promptVersion()).isEqualTo("narrative_v1");
        assertThat(res.narrative().get(0).cites()).isNotEmpty();
        assertThat(res.raw()).isEqualTo(om.readTree(example("draft_response.json")));
    }

    @Test
    void 정상_검증_요청_본문에_draft_id가_없고_기록_대상은_DRAFT다() throws Exception {
        server.enqueue(json(200, example("verify_response.json")));
        VerifyRequest request = om.readValue(example("verify_request.json"), VerifyRequest.class);

        VerifyResponse res = client.verify("DRF-000312", request);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/str/verify");
        assertThat(om.readTree(req.getBody().readUtf8())).isEqualTo(om.readTree(example("verify_request.json")));
        assertThat(res.status()).isEqualTo("CODE_FAILED");
        assertThat(res.passed()).isFalse();
        assertThat(res.raw()).isEqualTo(om.readTree(example("verify_response.json")));
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
        assertThat(row.getTargetId()).isEqualTo("DRF-000312");
        assertThat(row.getAiVersion()).isEqualTo(res.verifierVersion());
    }

    @Test
    void 실패_초안_응답_칸_타입이_틀리면_AI_BAD_RESPONSE_502() {
        server.enqueue(json(200, "{\"draft_id\":\"DRF-000312\",\"narrative\":\"문자열\"}"));

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
    }

    // --- 점수 (SCRUM-128, 규약 §3) -------------------------------------------

    /** 규약 §3 모양 응답 — 항목마다 OK, 맨 위 scoring_meta·produced_at. */
    private String scoreBody(long batchRunId, List<String> ids, String modelVersion, String producedAt) {
        ObjectNode body = om.createObjectNode().put("batch_run_id", batchRunId);
        ArrayNode scores = body.putArray("scores");
        for (String id : ids) {
            ObjectNode s = scores.addObject().put("alert_id", id).put("prediction_id", "p-" + id)
                    .put("score", 0.5).put("threshold", 0.7).put("data_quality_status", "OK");
            s.putNull("failure_code");
            s.putArray("reasons");
            s.putArray("evidence_transaction_ids").add(1L);
            s.putArray("context_transaction_ids");
        }
        body.putObject("scoring_meta").put("model_version", modelVersion).put("feature_version", "f-" + modelVersion)
                .put("server_version", "s1").put("latency_ms", 10);
        body.put("produced_at", producedAt);
        return body.toString();
    }

    private static void assertBadResponse(Throwable e) {
        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(ae.httpStatus()).isEqualTo(502);
        });
    }

    @Test
    void 정상_scores_예시_응답을_그대로_주면_새_칸을_읽고_요청은_batch_run_id와_alerts다() throws Exception {
        server.enqueue(json(200, example("score_response.json")));

        ScoresResponse res = client.scores(7L, List.of(alert("ALT-20220903-000142"), alert("ALT-20220903-000143")));

        JsonNode sent = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.path("batch_run_id").asLong()).isEqualTo(7L);
        assertThat(sent.path("alerts")).hasSize(2);
        assertThat(sent.at("/alerts/0/created_at").asText()).isEqualTo("2026-11-03T01:00:00Z");

        ScoreResult ok = res.scores().get(0);
        assertThat(ok.threshold()).isEqualTo(0.7);
        assertThat(ok.meta().modelVersion()).isEqualTo("xgb-2026-11-05");
        assertThat(ok.meta().featureVersion()).isEqualTo("f1");
        assertThat(ok.producedAt()).isEqualTo("2026-11-03T01:05:00Z");
        assertThat(ok.reasons()).hasSize(1);
        assertThat(ok.reasons().get(0).path("name").asText()).isEqualTo("account_in_counterparties_24h");
        assertThat(ok.evidenceTransactionIds()).containsExactly(104221L, 104233L, 104301L);
        ScoreResult failed = res.scores().get(1);
        assertThat(failed.dataQualityStatus()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(failed.score()).isNull();
        assertThat(failed.meta().featureVersion()).isEqualTo("f1");
    }

    @Test
    void 경계_501개는_묶음_2개로_가고_항목마다_자기_묶음의_scoring_meta와_produced_at이_붙는다() throws Exception {
        List<AlertPayload> alerts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add("A" + i);
            alerts.add(alert("A" + i));
        }
        server.enqueue(json(200, scoreBody(7L, ids.subList(0, 500), "M1", "2026-11-06T01:00:00Z")));
        server.enqueue(json(200, scoreBody(7L, ids.subList(500, 501), "M2", "2026-11-06T01:00:09Z")));

        ScoresResponse res = client.scores(7L, alerts);

        assertThat(res.scores()).hasSize(501);
        assertThat(om.readTree(server.takeRequest().getBody().readUtf8()).path("alerts")).hasSize(500);
        assertThat(om.readTree(server.takeRequest().getBody().readUtf8()).path("alerts")).hasSize(1);
        ScoreResult first = res.scores().get(0);
        ScoreResult last500 = res.scores().get(499);
        ScoreResult second = res.scores().get(500);
        assertThat(first.alertId()).isEqualTo("A0");
        assertThat(first.meta().modelVersion()).isEqualTo("M1");
        assertThat(first.meta().featureVersion()).isEqualTo("f-M1");
        assertThat(first.producedAt()).isEqualTo("2026-11-06T01:00:00Z");
        assertThat(last500.meta()).isSameAs(first.meta());
        assertThat(second.alertId()).isEqualTo("A500");
        assertThat(second.meta().modelVersion()).isEqualTo("M2");
        assertThat(second.meta().featureVersion()).isEqualTo("f-M2");
        assertThat(second.producedAt()).isEqualTo("2026-11-06T01:00:09Z");
    }

    @Test
    void 실패_응답_batch_run_id가_보낸_값과_다르면_AI_BAD_RESPONSE_502() {
        server.enqueue(json(200, scoreBody(8L, List.of("A1"), "M1", "2026-11-06T01:00:00Z")));

        assertBadResponse(catchThrowable(() -> client.scores(7L, List.of(alert("A1")))));
    }

    @Test
    void 실패_응답에_scoring_meta가_없으면_AI_BAD_RESPONSE_502() throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(scoreBody(7L, List.of("A1"), "M1", "2026-11-06T01:00:00Z"));
        body.remove("scoring_meta");
        server.enqueue(json(200, body.toString()));

        assertBadResponse(catchThrowable(() -> client.scores(7L, List.of(alert("A1")))));
    }

    @Test
    void 실패_응답에_보낸_alert_id가_빠지면_AI_BAD_RESPONSE_502() {
        server.enqueue(json(200, scoreBody(7L, List.of("A1"), "M1", "2026-11-06T01:00:00Z")));

        assertBadResponse(catchThrowable(() -> client.scores(7L, List.of(alert("A1"), alert("A2")))));
    }

    @Test
    void 실패_응답에_보내지_않은_alert_id가_있으면_AI_BAD_RESPONSE_502() {
        server.enqueue(json(200, scoreBody(7L, List.of("A1", "A9"), "M1", "2026-11-06T01:00:00Z")));

        assertBadResponse(catchThrowable(() -> client.scores(7L, List.of(alert("A1"), alert("A2")))));
    }

    // --- 경계: 재시도 (코드 기반) -------------------------------------------

    @Test
    void 경계_503_DB_UNAVAILABLE는_1회_재시도하고_두번째가_성공하면_돌려준다() {
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"db down\"}"));
        server.enqueue(json(200, scoreBody(7L, List.of("A1"), "M1", "2026-11-06T01:00:00Z")));

        ScoresResponse res = client.scores(7L, List.of(alert("A1")));

        assertThat(res.scores()).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(2);   // 최초 + 재시도 1회
    }

    @Test
    void 경계_재시도_뒤에도_실패하면_AI가_준_코드_그대로_502() {
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"db down\"}"));
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"db down\"}"));

        assertThatThrownBy(() -> client.scores(7L, List.of(alert("A1"))))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("DB_UNAVAILABLE");   // AI_UNAVAILABLE 아님 (②)
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // --- 재시도 안 함 -------------------------------------------------------

    @Test
    void 재시도없음_다른_5xx_코드는_그대로_502이고_재시도하지_않는다() {
        server.enqueue(json(500, "{\"code\":\"INTERNAL\",\"message\":\"boom\"}"));   // AI 500 INTERNAL (①)

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("INTERNAL");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);   // 재시도 안 함
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(500);
    }

    @Test
    void 재시도없음_4xx가_재시도코드를_담아도_재시도하지_않는다() {   // codex: 4xx veto
        server.enqueue(json(400, "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"bad request\"}"));

        assertThatThrownBy(() -> client.scores(7L, List.of(alert("A1"))))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("DB_UNAVAILABLE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);   // 4xx 는 재시도 안 함
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(400);
    }

    @Test
    void 실패_빈_200_본문은_성공으로_위장하지_않고_AI_BAD_RESPONSE_502() {   // codex: empty body
        server.enqueue(new MockResponse().setResponseCode(200));   // 본문 없음

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
    }

    @Test
    void 경계_GENERATION_IN_PROGRESS가_409가_아니면_매핑하지_않고_502() {   // codex: 409-only mapping
        server.enqueue(json(500, "{\"code\":\"GENERATION_IN_PROGRESS\",\"message\":\"x\"}"));

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("GENERATION_IN_PROGRESS");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
    }

    @Test
    void 경계_409_GENERATION_IN_PROGRESS는_재시도없이_409_DRAFT_IN_PROGRESS() {
        server.enqueue(json(409, "{\"code\":\"GENERATION_IN_PROGRESS\",\"message\":\"being generated\",\"request_id\":\"x\"}"));

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("DRAFT_IN_PROGRESS");
                    assertThat(e.httpStatus()).isEqualTo(409);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // --- 연결 거부 vs 연결 타임아웃 (③) ------------------------------------

    @Test
    void 연결_거부는_재시도_대상이고_AI_UNAVAILABLE_502() {
        WebClientAiClient.Classified c = client.classify(new ConnectException("Connection refused"));

        assertThat(c.retryable()).isTrue();
        assertThat(c.ex().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(c.ex().httpStatus()).isEqualTo(502);
    }

    @Test
    void 연결_타임아웃은_재시도하지_않고_AI_TIMEOUT() {
        // ConnectTimeoutException 은 ConnectException 의 하위 — 먼저 걸러지지 않으면 연결 거부로 잘못 잡힌다
        WebClientAiClient.Classified c = client.classify(new ConnectTimeoutException("connection timed out"));

        assertThat(c.retryable()).isFalse();
        assertThat(c.ex().code()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 응답_대기_타임아웃도_재시도하지_않는다() {
        WebClientAiClient.Classified c = client.classify(new TimeoutException("response timeout"));

        assertThat(c.retryable()).isFalse();
        assertThat(c.ex().code()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 연결_거부_엔드투엔드_재시도_뒤에도_실패하면_AI_UNAVAILABLE() {
        int port = server.getPort();
        try {
            server.shutdown();   // 포트를 닫아 연결 거부를 유발
        } catch (Exception ignore) {
            // 무시
        }
        WebClientAiClient dead = newClient("http://127.0.0.1:" + port + "/", 3);

        assertThatThrownBy(() -> dead.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo("AI_UNAVAILABLE");
                            assertThat(e.httpStatus()).isEqualTo(502);
                        });
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_UNAVAILABLE");
        assertThat(writer.rows.get(0).getRequestId()).isEqualTo(writer.rows.get(1).getRequestId());
    }

    @Test
    void 정상_초안_메타데이터와_요청id를_기록한다() throws Exception {
        server.enqueue(json(200, """
                {"draft_id":"D1","generation_id":"G1","generation_meta":{"prompt_version":"P1"}}
                """));
        client.createDraft(draftReq());
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getAttempt()).isEqualTo((short) 1);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getGenerationId()).isEqualTo("G1");
        assertThat(row.getAiVersion()).isEqualTo("P1");
        assertThat(row.getEndpoint()).isEqualTo("/v1/str/drafts");
        assertThat(row.getTargetType()).isEqualTo("DRAFT");
        assertThat(row.getTargetId()).isEqualTo(draftReq().draftId());
        assertThat(row.getRequestId()).isEqualTo(server.takeRequest().getHeader("X-Request-Id"));
        assertThat(row.getDurationMs()).isNotNegative();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void 경계_503_뒤_성공은_같은_요청id로_두_행이다() throws Exception {
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}"));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));
        client.createDraft(draftReq());
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(503, 200);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("LLM_UNAVAILABLE", null);
        String requestId = server.takeRequest().getHeader("X-Request-Id");
        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo(requestId);
        assertThat(writer.rows).extracting(AiCallLog::getRequestId).containsOnly(requestId);
        assertThat(writer.rows.get(0).getAiVersion()).isNull();
        assertThat(writer.rows.get(0).getGenerationId()).isNull();
    }

    @Test
    void 실패_응답_시간초과는_상태없이_한_행이다() {
        client = newClient(server.url("/").toString(), 1);
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}").setBodyDelay(2, TimeUnit.SECONDS));
        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_TIMEOUT");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void 실패_기록_실패가_성공과_AI예외를_바꾸지_않는다() {
        writer.fail = true;
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));
        assertThat(client.createDraft(draftReq()).draftId()).isEqualTo("D1");
        server.enqueue(json(409, "{\"code\":\"GENERATION_IN_PROGRESS\"}"));
        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("DRAFT_IN_PROGRESS");
                    assertThat(e.httpStatus()).isEqualTo(409);
                });
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(200, 409);
    }

    @Test
    void 정상_점수는_배치id와_scoring_meta_모델버전을_기록한다() {
        server.enqueue(json(200, scoreBody(7L, List.of("A1"), "M1", "2026-11-06T01:00:00Z")));
        client.scores(7L, List.of(alert("A1")));
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getTargetType()).isEqualTo("BATCH_RUN");
        assertThat(row.getTargetId()).isEqualTo("7");
        assertThat(row.getAiVersion()).isEqualTo("M1");
    }

    @Test
    void 정상_요약은_generation_meta의_prompt_version을_기록한다() {   // AI SummaryResponse.generation_meta (11/1 점검)
        server.enqueue(json(200, "{\"summary\":\"S\",\"generation_meta\":{\"prompt_version\":\"p-1\"}}"));
        client.caseSummary(new CaseSummaryRequest("C1", null, null, null, null));
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/cases/summary");
        assertThat(row.getAiVersion()).isEqualTo("p-1");
    }

}
