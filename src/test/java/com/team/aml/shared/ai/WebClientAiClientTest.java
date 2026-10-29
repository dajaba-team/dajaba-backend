package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebClientAiClient — 규약 §6 공통 동작을 MockWebServer 로 고정 (SCRUM-17).
 * 재시도는 HTTP 5xx 가 아니라 코드/원인으로, 연결 거부↔연결 타임아웃 구분, 409 → DRAFT_IN_PROGRESS.
 */
class WebClientAiClientTest {

    private final ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockWebServer server;
    private WebClientAiClient client;

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
        return new WebClientAiClient(wc, props, om);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private AlertPayload alert(String id) {
        return new AlertPayload(id, "010_ACCT", "ENT-1", "R02_FAN_IN_24H", "1", "FAN_IN",
                "2022-09-02T00:00:00", "2022-09-02T23:59:59", "2022-09-02T23:59:59", List.of(1L, 2L), null);
    }

    private StrDraftRequest draftReq() {
        return new StrDraftRequest("CASE-2022-000057", null, null, null, null, "조사 메모", null);
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

    @Test
    void 정상_scores_OK항목과_FAILED항목의_failure_code를_파싱한다() {
        server.enqueue(json(200, "{\"scores\":["
                + "{\"alert_id\":\"A1\",\"score\":0.91,\"data_quality_status\":\"OK\",\"failure_code\":null},"
                + "{\"alert_id\":\"A2\",\"score\":null,\"data_quality_status\":\"FAILED\",\"failure_code\":\"TRANSACTION_NOT_FOUND\"}]}"));

        ScoresResponse res = client.scores(7L, List.of(alert("A1"), alert("A2")));

        assertThat(res.scores()).hasSize(2);
        assertThat(res.scores().get(0).dataQualityStatus()).isEqualTo("OK");
        assertThat(res.scores().get(0).failureCode()).isNull();
        assertThat(res.scores().get(1).dataQualityStatus()).isEqualTo("FAILED");
        assertThat(res.scores().get(1).failureCode()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(res.scores().get(1).score()).isNull();
    }

    // --- 경계: 재시도 (코드 기반) -------------------------------------------

    @Test
    void 경계_503_DB_UNAVAILABLE는_1회_재시도하고_두번째가_성공하면_돌려준다() {
        server.enqueue(json(503, "{\"code\":\"DB_UNAVAILABLE\",\"message\":\"db down\"}"));
        server.enqueue(json(200, "{\"scores\":[{\"alert_id\":\"A1\",\"score\":0.5,\"data_quality_status\":\"OK\"}]}"));

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
                        e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
    }
}
