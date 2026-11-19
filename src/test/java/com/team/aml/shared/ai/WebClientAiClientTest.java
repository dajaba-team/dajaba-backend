package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import com.team.aml.shared.config.AmlProperties;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.unix.Errors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
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
        return newClient(baseUrl, timeoutSeconds, timeoutSeconds);
    }

    private WebClientAiClient newClient(String baseUrl, int timeoutSeconds, int askTimeoutSeconds) {
        var wc = org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(baseUrl).build();
        var props = new AmlProperties(
                new AmlProperties.Ai(baseUrl, "test-internal-key-0123456789abcd", timeoutSeconds, 500, false,
                        askTimeoutSeconds, false, 5),
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

    // --- 점수 응답 칸 확인 (SCRUM-204, AI 계약 scores.py 기준 + threshold 0~1) ------

    /** A1 한 항목짜리 정상 응답을 받아 칸 하나만 바꾼다. */
    private String scoreBodyWith(java.util.function.Consumer<ObjectNode> edit) throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(scoreBody(7L, List.of("A1"), "M1", "2026-11-06T01:00:00Z"));
        edit.accept(body);
        return body.toString();
    }

    private static ObjectNode item(ObjectNode body) {
        return (ObjectNode) body.withArray("scores").get(0);
    }

    private static ObjectNode meta(ObjectNode body) {
        return (ObjectNode) body.get("scoring_meta");
    }

    @Test
    void 경계_threshold_0과_1_빈_model_version_FAILED_항목_score_null은_통과한다() throws Exception {
        List<java.util.function.Consumer<ObjectNode>> edits = List.of(
                b -> item(b).put("threshold", 0),
                b -> item(b).put("threshold", 1.0),
                b -> meta(b).put("model_version", "").put("feature_version", "").put("server_version", ""),
                b -> meta(b).put("latency_ms", 0),
                b -> item(b).put("data_quality_status", "FAILED").put("failure_code", "COMPUTE_ERROR")
                        .putNull("prediction_id").putNull("score"));
        for (var edit : edits) {
            server.enqueue(json(200, scoreBodyWith(edit)));
            assertThat(client.scores(7L, List.of(alert("A1"))).scores()).hasSize(1);
        }
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnlyNulls();
    }

    @Test
    void 실패_점수_응답_칸이_틀리면_AI_BAD_RESPONSE_502이고_실패_행_하나만_남고_재시도하지_않는다() throws Exception {
        record Bad(String field, String value, java.util.function.Consumer<ObjectNode> edit) {}
        List<Bad> bads = List.of(
                new Bad("threshold", "abc", b -> item(b).put("threshold", "abc")),
                new Bad("threshold", "1.5", b -> item(b).put("threshold", 1.5)),
                new Bad("threshold", "-0.1", b -> item(b).put("threshold", -0.1)),
                new Bad("threshold", "{}", b -> item(b).putObject("threshold")),
                new Bad("threshold", "true", b -> item(b).put("threshold", true)),
                new Bad("scoring_meta", "{}", b -> b.putObject("scoring_meta")),
                new Bad("model_version", "123", b -> meta(b).put("model_version", 123)),
                new Bad("server_version", "null", b -> meta(b).putNull("server_version")),
                new Bad("latency_ms", "-1", b -> meta(b).put("latency_ms", -1)),
                new Bad("latency_ms", "1.5", b -> meta(b).put("latency_ms", 1.5)),
                new Bad("score", "x", b -> item(b).put("score", "x")),
                new Bad("score", "1.2", b -> item(b).put("score", 1.2)),
                new Bad("score", "null", b -> item(b).putNull("score")),   // OK 항목인데 점수 없음
                new Bad("evidence_transaction_ids", "null", b -> item(b).putArray("evidence_transaction_ids").add(1L).addNull()),
                new Bad("context_transaction_ids", "zz", b -> item(b).putArray("context_transaction_ids").add("zz")),
                new Bad("score", "0.5", b -> item(b).put("data_quality_status", "FAILED")   // FAILED 인데 점수 있음
                        .put("failure_code", "COMPUTE_ERROR").putNull("prediction_id").put("score", 0.5)),
                new Bad("scores", "{}", b -> b.putObject("scores")),
                new Bad("evidence_transaction_ids", "7", b -> item(b).put("evidence_transaction_ids", 7)),
                new Bad("context_transaction_ids", "{}", b -> item(b).putObject("context_transaction_ids")),
                new Bad("evidence_transaction_ids", "0", b -> item(b).putArray("evidence_transaction_ids").add(0L)),
                new Bad("context_transaction_ids", "-1", b -> item(b).putArray("context_transaction_ids").add(-1L)));

        for (Bad bad : bads) {
            writer.rows.clear();
            int before = server.getRequestCount();
            server.enqueue(json(200, scoreBodyWith(bad.edit())));

            Throwable e = catchThrowable(() -> client.scores(7L, List.of(alert("A1"))));

            assertBadResponse(e);
            assertThat(e.getMessage()).as(bad.field()).contains(bad.field());
            if (bad.value().length() > 1) assertThat(e.getMessage()).as(bad.field()).doesNotContain(bad.value());
            assertThat(server.getRequestCount() - before).as(bad.field()).isEqualTo(1);   // 재시도 없음
            assertThat(writer.rows).as(bad.field()).hasSize(1);
            AiCallLog row = writer.rows.get(0);
            assertThat(row.getStatusCode()).as(bad.field()).isEqualTo(200);
            assertThat(row.getErrorCode()).as(bad.field()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(row.getAttempt()).as(bad.field()).isEqualTo((short) 1);
        }
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
    void 재시도없음_4xx가_재시도코드를_담아도_재시도하지_않는다() {
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
    void 실패_빈_200_본문은_성공으로_위장하지_않고_AI_BAD_RESPONSE_502() {
        server.enqueue(new MockResponse().setResponseCode(200));   // 본문 없음

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
    }

    @Test
    void 경계_GENERATION_IN_PROGRESS가_409가_아니면_매핑하지_않고_502() {
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

    // --- 연결 단계 실패 · 보낸 뒤 끊김 (SCRUM-229) ----------------------------

    private static WebClientRequestException wrapped(Throwable cause) {
        return new WebClientRequestException(cause, HttpMethod.POST, URI.create("http://ai:8000/v1/str/drafts"), new HttpHeaders());
    }

    private static void assertUnavailable(WebClientAiClient.Classified c, boolean retryable) {
        assertThat(c.ex().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(c.ex().httpStatus()).isEqualTo(502);
        assertThat(c.ex().getMessage()).isEqualTo("AI 서버 응답 없음. 잠시 후 다시 시도");
        assertThat(c.retryable()).isEqualTo(retryable);
    }

    @Test
    void 정상_이름_조회_실패는_연결_거부처럼_AI_UNAVAILABLE이고_재시도_대상() {
        UnknownHostException e = new UnknownHostException("ai");
        assertUnavailable(client.classify(e), true);
        assertUnavailable(client.classify(wrapped(e)), true);
    }

    @Test
    void 정상_경로_없음도_연결_단계_실패라_재시도_대상() {
        // NoRouteToHostException 은 SocketException 하위(ConnectException 아님)
        assertUnavailable(client.classify(wrapped(new NoRouteToHostException("no route"))), true);
    }

    @Test
    void 경계_싸인_연결_타임아웃은_그대로_AI_TIMEOUT이고_재시도하지_않는다() {
        WebClientAiClient.Classified c = client.classify(wrapped(new ConnectTimeoutException("connection timed out")));
        assertThat(c.ex().code()).isEqualTo("AI_TIMEOUT");
        assertThat(c.retryable()).isFalse();
    }

    @Test
    void 경계_보낸_뒤_끊김은_AI_UNAVAILABLE이고_재시도하지_않는다() {
        assertUnavailable(client.classify(wrapped(PrematureCloseException.TEST_EXCEPTION)), false);
        assertUnavailable(client.classify(PrematureCloseException.TEST_EXCEPTION), false);   // 2xx 본문 중 끊김은 안 싸여 옴
        assertUnavailable(client.classify(wrapped(new SocketException("Connection reset"))), false);   // 맥(NIO)
        // 리눅스(epoll) ECONNRESET — 맥엔 네이티브 라이브러리가 없어 생성자 대신 mock
        assertUnavailable(client.classify(wrapped(Mockito.mock(Errors.NativeIoException.class))), false);
    }

    @Test
    void 실패_분류_안_된_예외는_AI_BAD_RESPONSE이고_메시지에_클래스_이름이_없다() {
        WebClientAiClient.Classified c = client.classify(new IllegalStateException("boom"));
        assertThat(c.ex().code()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(c.ex().httpStatus()).isEqualTo(502);
        assertThat(c.ex().getMessage()).isEqualTo("AI 응답 처리 실패").doesNotContain("IllegalStateException").doesNotContain("boom");
        assertThat(c.retryable()).isFalse();
    }

    @Test
    void 이름_조회_실패_엔드투엔드_재시도_뒤에도_실패하면_AI_UNAVAILABLE() {
        WebClientAiClient dead = newClient("http://ai-nohost.invalid:8000/", 3);   // .invalid 는 풀리지 않는 예약 도메인

        assertThatThrownBy(() -> dead.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_UNAVAILABLE");
                    assertThat(e.httpStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).doesNotContain("Exception");
                });
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_UNAVAILABLE");
        assertThat(writer.rows.get(0).getRequestId()).isEqualTo(writer.rows.get(1).getRequestId());
    }

    @Test
    void 경계_요청을_받은_뒤_끊으면_한_번만_보내고_AI_UNAVAILABLE() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));   // 재시도하면 이게 성공으로 잡힌다

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void 경계_2xx_본문_중_끊기면_상태는_실제_2xx이고_AI_UNAVAILABLE_한_행() {
        server.enqueue(json(200, "{\"draft_id\":\"D1\",\"generation_id\":\"G1\",\"pad\":\"" + "x".repeat(20_000) + "\"}")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        assertThatThrownBy(() -> client.createDraft(draftReq()))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isEqualTo("AI_UNAVAILABLE");
        assertThat(row.getGenerationId()).isNull();
        assertThat(row.getAiVersion()).isNull();
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

    // --- 2xx 뒤 응답 확인 실패 = 실패 행 (SCRUM-128 2차) ----------------------------

    /** 시도 한 번, 행 하나: status=실제 2xx, error=AI_BAD_RESPONSE. 재시도 없음. */
    private void assertOneBadResponseRow(Throwable e, String endpoint) {
        assertBadResponse(e);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo(endpoint);
        assertThat(row.getAttempt()).isEqualTo((short) 1);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @Test
    void 실패_초안_2xx에_draft_id가_없으면_실패_행이고_본문_메타는_남는다() {
        server.enqueue(json(200, "{\"generation_id\":\"G9\",\"generation_meta\":{\"prompt_version\":\"P9\"}}"));

        assertOneBadResponseRow(catchThrowable(() -> client.createDraft(draftReq())), "/v1/str/drafts");
        assertThat(writer.rows.get(0).getGenerationId()).isEqualTo("G9");
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("P9");
    }

    @Test
    void 실패_검증_2xx에_verification_id가_없으면_실패_행() throws Exception {
        server.enqueue(json(200, "{\"generation_id\":\"g-1\",\"verifier_version\":\"V1\"}"));
        VerifyRequest request = om.readValue(example("verify_request.json"), VerifyRequest.class);

        assertOneBadResponseRow(catchThrowable(() -> client.verify("DRF-000312", request)), "/v1/str/verify");
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("V1");
    }

    /** verify_response.json 을 한 칸만 바꿔 돌려준다 — 나머지는 계약 예시 그대로(보낸 generation_id·revision_no 와 같음). */
    private void enqueueVerifyWith(String field, Object value) throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(example("verify_response.json"));
        body.set(field, om.valueToTree(value));
        server.enqueue(json(200, body.toString()));
    }

    @Test
    void 실패_검증_응답_status_produced_at_해시_모양이_틀리면_실패_행이고_재시도하지_않는다() throws Exception {   // #95 리뷰
        VerifyRequest request = om.readValue(example("verify_request.json"), VerifyRequest.class);
        Object[][] wrong = {
                {"status", "OK"},
                {"produced_at", "2026-11-05T23:02:14+09:00"},
                {"produced_at", "어제"},
                {"narrative_sha256", "ABC"},
        };
        for (Object[] w : wrong) {
            writer.rows.clear();
            int before = server.getRequestCount();
            enqueueVerifyWith((String) w[0], w[1]);

            Throwable e = catchThrowable(() -> client.verify("DRF-000312", request));

            assertBadResponse(e);
            assertThat(e).hasMessageContaining((String) w[0]);
            assertThat(server.getRequestCount() - before).isEqualTo(1);
            assertThat(writer.rows).singleElement().satisfies(row -> {
                assertThat(row.getStatusCode()).isEqualTo(200);
                assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
            });
        }
    }

    @Test
    void 실패_검증_응답_generation_id나_revision_no가_보낸_값과_다르면_실패_행() throws Exception {   // #95 리뷰
        VerifyRequest request = om.readValue(example("verify_request.json"), VerifyRequest.class);

        enqueueVerifyWith("generation_id", "g-other");
        assertOneBadResponseRow(catchThrowable(() -> client.verify("DRF-000312", request)), "/v1/str/verify");

        writer.rows.clear();
        enqueueVerifyWith("revision_no", 3);
        Throwable e = catchThrowable(() -> client.verify("DRF-000312", request));
        assertBadResponse(e);
        assertThat(e).hasMessageContaining("revision_no");
        assertThat(writer.rows).singleElement().satisfies(row -> assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE"));
    }

    @Test
    void 실패_요약_2xx에_case_no가_없으면_실패_행() {
        server.enqueue(json(200, "{\"summary_lines\":[],\"generation_meta\":{\"prompt_version\":\"summary_v1\"}}"));

        assertOneBadResponseRow(catchThrowable(() -> client.caseSummary(new CaseSummaryRequest("CASE-2022-000001",
                null, List.of(), om.createArrayNode(), 0, null))), "/v1/cases/summary");
    }

    @Test
    void 실패_점수_2xx에_alert_id_집합이_다르면_실패_행() {
        server.enqueue(json(200, scoreBody(7L, List.of("A1", "A9"), "M1", "2026-11-06T01:00:00Z")));

        assertOneBadResponseRow(catchThrowable(() -> client.scores(7L, List.of(alert("A1"), alert("A2")))), "/v1/scores");
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("M1");
    }

    // --- AI 4xx 이름 충돌 → AI_ 붙여 502 (SCRUM-128 2차) -----------------------------

    private void assertRenamed(int status, String aiCode, String expected) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + aiCode + "\",\"message\":\"m\",\"request_id\":\"r\"}"));
        VerifyRequest request = om.readValue(example("verify_request.json"), VerifyRequest.class);

        assertThatThrownBy(() -> client.verify("DRF-000312", request))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo(expected);
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsExactly(status);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(expected);
    }

    @Test
    void 실패_AI_404_DRAFT_NOT_FOUND는_AI_DRAFT_NOT_FOUND_502() throws Exception {
        assertRenamed(404, "DRAFT_NOT_FOUND", "AI_DRAFT_NOT_FOUND");
    }

    @Test
    void 실패_AI_400_INVALID_REQUEST는_AI_INVALID_REQUEST_502() throws Exception {
        assertRenamed(400, "INVALID_REQUEST", "AI_INVALID_REQUEST");
    }

    @Test
    void 실패_AI_401_UNAUTHENTICATED는_AI_UNAUTHENTICATED_502() throws Exception {
        assertRenamed(401, "UNAUTHENTICATED", "AI_UNAUTHENTICATED");
    }

    @Test
    void 경계_이름이_겹치지_않는_AI_4xx_코드는_그대로_502() throws Exception {
        assertRenamed(404, "SOMETHING_ELSE", "SOMETHING_ELSE");
    }

    // --- X-Budget-Ms (SCRUM-128 2차, 11/8 리뷰) -------------------------------------

    private static long budgetMs(RecordedRequest req) {
        String v = req.getHeader("X-Budget-Ms");
        assertThat(v).as("X-Budget-Ms").isNotNull().containsOnlyDigits();
        return Long.parseLong(v);
    }

    @Test
    void 정상_매_시도에_남은_예산_ms를_X_Budget_Ms로_보내고_재시도는_줄어든_값() throws Exception {
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}").setBodyDelay(50, TimeUnit.MILLISECONDS));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.createDraft(draftReq());

        long first = budgetMs(server.takeRequest());
        long second = budgetMs(server.takeRequest());
        assertThat(first).isPositive().isLessThanOrEqualTo(5_000);   // 예산 5초
        assertThat(second).isPositive().isLessThan(first);
    }

    @Test
    void 정상_health에도_X_Budget_Ms를_보낸다() throws Exception {
        server.enqueue(json(200, "{\"status\":\"ok\"}"));

        client.health();

        assertThat(budgetMs(server.takeRequest())).isPositive().isLessThanOrEqualTo(5_000);
    }

    // --- health (SCRUM-22, 미결 28) — AI main.py health() 실제 칸 ------------------

    @Test
    void 정상_health는_AI_실제_칸을_읽는다() {
        server.enqueue(json(200, """
                {"status":"ok","db":"ok","llm":"missing_key","server_version":"a1b2c3d",
                 "model_version":"xgb-2026-11-05","feature_version":"f1","retrieval_version":"r1"}
                """));

        HealthResponse h = client.health();

        assertThat(h.status()).isEqualTo("ok");
        assertThat(h.db()).isEqualTo("ok");
        assertThat(h.llm()).isEqualTo("missing_key");
        assertThat(h.serverVersion()).isEqualTo("a1b2c3d");
        assertThat(h.modelVersion()).isEqualTo("xgb-2026-11-05");
        assertThat(h.featureVersion()).isEqualTo("f1");
        assertThat(h.retrievalVersion()).isEqualTo("r1");
    }

    @Test
    void 경계_health_retrieval_version이_null이면_null_그대로() {
        server.enqueue(json(200, """
                {"status":"ok","db":"down:OperationalError","llm":"ok","server_version":"a1b2c3d",
                 "model_version":"xgb-2026-11-05","feature_version":"f1","retrieval_version":null}
                """));

        HealthResponse h = client.health();

        assertThat(h.retrievalVersion()).isNull();
        assertThat(h.db()).isEqualTo("down:OperationalError");
        assertThat(h.featureVersion()).isEqualTo("f1");
    }

    @Test
    void 정상_요약은_generation_meta의_prompt_version을_기록한다() {   // AI SummaryResponse.generation_meta (11/1 점검)
        server.enqueue(json(200, "{\"case_no\":\"CASE-2022-000001\",\"generation_meta\":{\"prompt_version\":\"p-1\"}}"));
        client.caseSummary(new CaseSummaryRequest("CASE-2022-000001", null, List.of(), null, 0, null));
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/cases/summary");
        assertThat(row.getAiVersion()).isEqualTo("p-1");
        assertThat(row.getTargetType()).isEqualTo("CASE");
        assertThat(row.getTargetId()).isEqualTo("CASE-2022-000001");
    }

    @Test
    void 정상_요약_요청은_규약_6_1_칸으로_나가고_응답은_쓰는_칸과_원문으로_돌아온다() throws Exception {   // v0.4, 11/6
        server.enqueue(json(200, """
                {"produced_at":"2026-11-06T09:00:00Z","case_no":"CASE-2022-000001","generation_id":"GEN-1",
                 "facts":{"inbound":{"count":2}},"summary_lines":["첫 줄","둘째 줄"],"model_focus":"모델은 inflow_outflow_ratio_2h 신호를 가장 크게 봄",
                 "code_checks":[{"check":"COUNT_MATCH","match":true}],
                 "generation_meta":{"prompt_version":"summary_v1","llm_model":"m"}}
                """));
        AlertPayload alert = new AlertPayload("ALT-20220902-000001", "10_A", null, "R02_FAN_IN_24H", "1", "FAN_IN",
                "2022-09-01T00:00:00", "2022-09-02T00:00:00", "2022-09-02T00:00:00", List.of(1L, 2L),
                om.createObjectNode(), "2026-11-01T00:00:00Z");

        CaseSummaryResponse r = client.caseSummary(new CaseSummaryRequest("CASE-2022-000001", alert, List.of(1L, 2L),
                om.createArrayNode(), 3, null));

        JsonNode sent = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.fieldNames()).toIterable().containsExactlyInAnyOrder("case_no", "alert",
                "evidence_transaction_ids", "reasons", "prior_alert_count", "reporting_institution");
        assertThat(sent.get("prior_alert_count").asInt()).isEqualTo(3);
        assertThat(sent.get("reasons").isArray()).isTrue();
        assertThat(r.caseNo()).isEqualTo("CASE-2022-000001");
        assertThat(r.generationId()).isEqualTo("GEN-1");
        assertThat(r.producedAt()).isEqualTo("2026-11-06T09:00:00Z");
        assertThat(r.summaryLines()).containsExactly("첫 줄", "둘째 줄");
        assertThat(r.codeChecks().get(0).get("match").asBoolean()).isTrue();
        assertThat(r.promptVersion()).isEqualTo("summary_v1");
        assertThat(r.raw().path("facts").path("inbound").path("count").asInt()).isEqualTo(2);
    }

    @Test
    void 실패_요약_응답에_case_no가_없으면_AI_BAD_RESPONSE() {
        server.enqueue(json(200, "{\"summary_lines\":[]}"));
        assertThatThrownBy(() -> client.caseSummary(new CaseSummaryRequest("CASE-2022-000001", null, List.of(),
                om.createArrayNode(), 0, null)))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE"));
    }

    // --- X-Request-Id = 요청 MDC 값 (SCRUM-272) ------------------------------

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 정상_요청_MDC의_request_id를_헤더와_기록에_그대로_쓴다() throws Exception {
        MDC.put("request_id", "rid-from-filter-1");
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.createDraft(draftReq());

        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo("rid-from-filter-1");
        assertThat(writer.rows).extracting(AiCallLog::getRequestId).containsExactly("rid-from-filter-1");
        assertThat(MDC.get("request_id")).isEqualTo("rid-from-filter-1");   // 호출이 MDC 를 바꾸지 않는다
    }

    @Test
    void 경계_재시도해도_같은_MDC_값이다() throws Exception {
        MDC.put("request_id", "rid-from-filter-2");
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}"));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.createDraft(draftReq());

        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo("rid-from-filter-2");
        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo("rid-from-filter-2");
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getRequestId).containsOnly("rid-from-filter-2");
    }

    @Test
    void 경계_한_요청에서_AI를_두_번_부르면_같은_request_id이고_endpoint로_구분된다() throws Exception {
        MDC.put("request_id", "rid-from-filter-3");
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));
        server.enqueue(json(200, example("verify_response.json")));

        client.createDraft(draftReq());
        client.verify("DRF-000312", om.readValue(example("verify_request.json"), VerifyRequest.class));

        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo("rid-from-filter-3");
        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo("rid-from-filter-3");
        assertThat(writer.rows).extracting(AiCallLog::getRequestId).containsOnly("rid-from-filter-3");
        assertThat(writer.rows).extracting(AiCallLog::getEndpoint).containsExactly("/v1/str/drafts", "/v1/str/verify");
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 1);
    }

    @Test
    void 경계_MDC가_없으면_배치처럼_호출마다_새_UUID다() throws Exception {
        MDC.clear();
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.createDraft(draftReq());
        client.createDraft(draftReq());

        String first = server.takeRequest().getHeader("X-Request-Id");
        String second = server.takeRequest().getHeader("X-Request-Id");
        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(second).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").isNotEqualTo(first);
        assertThat(writer.rows).extracting(AiCallLog::getRequestId).containsExactly(first, second);
    }

    @Test
    void 경계_MDC가_빈_값이면_새_UUID다() throws Exception {
        MDC.put("request_id", "");
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.createDraft(draftReq());

        assertThat(server.takeRequest().getHeader("X-Request-Id"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // --- 질문 답변 ask (SCRUM-291) — 30초 예산·응답 확인·기록 --------------------------

    private static final String ANSWER_TEXT = "입금 상대 계좌는 3개예요.";

    private AskRequest askReq(String question) throws Exception {
        CaseSummaryRequest summary = om.treeToValue(
                om.readTree(Files.readString(Path.of("contracts/examples/summary_request.json"))), CaseSummaryRequest.class);
        return AskRequest.of(summary, question);
    }

    private static String askExample(String status) throws Exception {
        return Files.readString(Path.of("contracts/examples/ask_response_" + status + ".json"));
    }

    @Test
    void 정상_ask는_규약_5_4_칸으로_보내고_질문_공백을_자르지_않고_응답을_읽는다() throws Exception {
        server.enqueue(json(200, askExample("answered")));

        AskResponse r = client.ask(askReq("  입금 상대가 몇 곳이야? "));

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/cases/ask");
        assertThat(req.getHeader("X-Internal-Key")).isEqualTo("test-internal-key-0123456789abcd");
        JsonNode sent = om.readTree(req.getBody().readUtf8());
        assertThat(sent.fieldNames()).toIterable().containsExactlyInAnyOrder("case_no", "alert",
                "evidence_transaction_ids", "reasons", "prior_alert_count", "reporting_institution", "question");
        assertThat(sent.get("question").textValue()).isEqualTo("  입금 상대가 몇 곳이야? ");
        assertThat(r.status()).isEqualTo(AskStatus.ANSWERED);
        assertThat(r.answer()).isEqualTo(ANSWER_TEXT);
        assertThat(r.evidenceIds()).containsExactly(104221L, 104233L, 104301L);
        assertThat(r.generationMeta().promptVersion()).isEqualTo("ask_answer_v1");
        assertThat(r.raw().path("generation_meta").path("tokens_in").asInt()).isEqualTo(1450);

        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/cases/ask");
        assertThat(row.getTargetType()).isEqualTo("CASE");
        assertThat(row.getTargetId()).isEqualTo("CASE-2022-000057");
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getGenerationId()).isEqualTo("g-91ab33c0");
        assertThat(row.getAiVersion()).isEqualTo("ask_answer_v1");
    }

    @Test
    void 경계_ask_OUT_OF_SCOPE는_ai_version에_plan_prompt_version을_남긴다() throws Exception {
        server.enqueue(json(200, askExample("out_of_scope")));

        AskResponse r = client.ask(askReq("STR 보내야 해?"));

        assertThat(r.status()).isEqualTo(AskStatus.OUT_OF_SCOPE);
        assertThat(r.outOfScopeReason()).isEqualTo(OutOfScopeReason.JUDGMENT);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("ask_plan_v1");
        assertThat(writer.rows.get(0).getErrorCode()).isNull();
    }

    @Test
    void 경계_ask_NO_DATA와_UNVERIFIED는_prompt_version을_남긴다() throws Exception {
        server.enqueue(json(200, askExample("no_data")));
        server.enqueue(json(200, askExample("unverified")));

        assertThat(client.ask(askReq("q")).status()).isEqualTo(AskStatus.NO_DATA);
        assertThat(client.ask(askReq("q")).status()).isEqualTo(AskStatus.UNVERIFIED);

        assertThat(writer.rows).extracting(AiCallLog::getAiVersion).containsExactly("ask_answer_v1", "ask_answer_v1");
    }

    @Test
    void 경계_ask는_자기_예산으로_끊고_같은_지연에도_요약은_90초_쪽_예산이라_성공한다() throws Exception {
        client = newClient(server.url("/").toString(), 5, 1);   // 기본 5초 · 질문 답변 1초 (90초·30초를 줄인 모양)
        server.enqueue(json(200, askExample("answered")).setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.ask(askReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_TIMEOUT");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);   // 타임아웃은 재시도 안 함
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getEndpoint()).isEqualTo("/v1/cases/ask");
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");

        server.enqueue(json(200, "{\"case_no\":\"CASE-2022-000001\",\"generation_meta\":{\"prompt_version\":\"p-1\"}}")
                .setBodyDelay(2, TimeUnit.SECONDS));
        assertThat(client.caseSummary(new CaseSummaryRequest("CASE-2022-000001", null, List.of(), null, 0, null))
                .caseNo()).isEqualTo("CASE-2022-000001");
    }

    @Test
    void 정상_ask의_X_Budget_Ms는_질문_답변_예산_기준이고_초안은_기본_예산_기준이다() throws Exception {
        client = newClient(server.url("/").toString(), 5, 2);
        server.enqueue(json(200, askExample("answered")));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.ask(askReq("q"));
        client.createDraft(draftReq());

        assertThat(budgetMs(server.takeRequest())).isPositive().isLessThanOrEqualTo(2_000);
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(2_000).isLessThanOrEqualTo(5_000);
    }

    @Test
    void 경계_ask_LLM_UNAVAILABLE는_1회_재시도하고_같은_요청id로_두_행이다() throws Exception {
        server.enqueue(json(503, "{\"code\":\"LLM_UNAVAILABLE\"}"));
        server.enqueue(json(200, askExample("answered")));

        assertThat(client.ask(askReq("q")).status()).isEqualTo(AskStatus.ANSWERED);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly("LLM_UNAVAILABLE", null);
        assertThat(writer.rows).extracting(AiCallLog::getEndpoint).containsOnly("/v1/cases/ask");
        String first = server.takeRequest().getHeader("X-Request-Id");
        assertThat(server.takeRequest().getHeader("X-Request-Id")).isEqualTo(first);
    }

    @Test
    void 재시도없음_ask_다른_5xx는_재시도하지_않고_코드_그대로_502() throws Exception {
        server.enqueue(json(500, "{\"code\":\"INTERNAL\"}"));

        assertThatThrownBy(() -> client.ask(askReq("q")))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("INTERNAL"));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void 실패_ask_2xx_응답_확인이_틀리면_실패_행이고_재시도_없고_로그에_질문과_답이_없다(
            org.springframework.boot.test.system.CapturedOutput out) throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(askExample("answered"));
        body.putArray("evidence_ids").add(104221L).add(999999L);   // 보낸 근거 밖
        server.enqueue(json(200, body.toString()));
        String question = "입금 상대 비밀질문문장?";

        assertOneBadResponseRow(catchThrowable(() -> client.ask(askReq(question))), "/v1/cases/ask");
        assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("ask_answer_v1");   // 본문 메타는 남음
        assertThat(writer.rows.get(0).getGenerationId()).isEqualTo("g-91ab33c0");
        assertThat(out.getAll()).contains("/v1/cases/ask").doesNotContain(question).doesNotContain(ANSWER_TEXT);
    }

    // --- 2차: 원소·길이 위반은 실패 행 · 버전 칸 모양이 아니면 ai_version null --------------

    @Test
    void 실패_ask_cites_null_원소_공백_원소_401자_답은_실패_행이고_재시도_없다() throws Exception {
        List<java.util.function.Consumer<ObjectNode>> edits = List.of(
                b -> b.putArray("cites").addNull(),
                b -> b.putArray("cites").add("   "),
                b -> b.put("answer", "가".repeat(401)));
        for (var edit : edits) {
            writer.rows.clear();
            int before = server.getRequestCount();
            ObjectNode body = (ObjectNode) om.readTree(askExample("answered"));
            edit.accept(body);
            server.enqueue(json(200, body.toString()));

            Throwable e = catchThrowable(() -> client.ask(askReq("q")));

            assertBadResponse(e);
            assertThat(server.getRequestCount() - before).isEqualTo(1);
            assertThat(writer.rows).hasSize(1);
            assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(200);
            assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
            assertThat(writer.rows.get(0).getAiVersion()).isEqualTo("ask_answer_v1");
        }
    }

    @Test
    void 실패_ask_두_버전_칸에_긴_문장이_오면_실패_행의_ai_version은_null이다() throws Exception {
        String sentence = "입금 상대 계좌는 세 곳이고 모두 같은 날 짧은 간격으로 들어왔으며 금액이 거의 같아서 보고 기준을 피하려고 쪼갠 거래로 보입니다.";
        assertThat(sentence.length()).isGreaterThan(WebClientAiClient.AI_VERSION_MAX);
        ObjectNode body = (ObjectNode) om.readTree(askExample("answered"));
        ObjectNode meta = (ObjectNode) body.get("generation_meta");
        meta.put("prompt_version", sentence);
        meta.put("plan_prompt_version", sentence);
        body.putArray("cites");   // 응답 확인도 실패
        server.enqueue(json(200, body.toString()));

        assertOneBadResponseRow(catchThrowable(() -> client.ask(askReq("q"))), "/v1/cases/ask");
        assertThat(writer.rows.get(0).getAiVersion()).isNull();
    }

    @Test
    void 경계_ask_OUT_OF_SCOPE_plan_prompt_version이_문자열이_아니거나_길면_null이고_64자는_그대로() {
        ObjectNode meta = om.createObjectNode().putNull("prompt_version");
        meta.put("plan_prompt_version", 3);
        assertThat(WebClientAiClient.askVersion(meta)).isNull();
        meta.put("plan_prompt_version", "p".repeat(65));
        assertThat(WebClientAiClient.askVersion(meta)).isNull();
        meta.put("plan_prompt_version", "p".repeat(64));
        assertThat(WebClientAiClient.askVersion(meta)).isEqualTo("p".repeat(64));
        meta.put("prompt_version", "a".repeat(65));   // 답 버전이 있는데 모양이 틀리면 계획 버전으로 바꾸지 않고 null
        assertThat(WebClientAiClient.askVersion(meta)).isNull();
    }
}
