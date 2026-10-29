package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import com.team.aml.shared.config.AmlProperties;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * 실제 AI 서버 호출 (aml.ai.mock=false, 규약 v0.2 §6). 유스케이스는 AiClient 인터페이스만 본다.
 *
 * 공통 동작:
 *  - 헤더 X-Internal-Key, X-Request-Id(UUID, 재시도해도 같은 값)
 *  - 화면 요청 전체 90초(재시도 포함) = aml.ai.timeout-seconds. 점수는 500건 묶음마다 같은 한도.
 *  - 재시도 1회, 남은 시간 안에서만. 판별은 HTTP 5xx 가 아니라 코드/원인으로:
 *      재시도 O: LLM_UNAVAILABLE·MODEL_UNAVAILABLE·DB_UNAVAILABLE, 또는 연결 거부(ConnectException)
 *      재시도 X: 그 밖의 5xx·4xx·409, 연결 타임아웃(ConnectTimeoutException)·응답 타임아웃
 *  - 실패 매핑: AI 가 준 코드 그대로 502. 연결 거부로 끝나면 AI_UNAVAILABLE 502, 타임아웃은 AI_TIMEOUT 502.
 *  - AI 409 GENERATION_IN_PROGRESS → 409 DRAFT_IN_PROGRESS (재시도 없음).
 *
 * ai_call_logs 적재는 SCRUM-18. 여기서는 메타데이터 로그만 남기고 요청·응답 본문은 로그하지 않는다 (D5).
 */
@Component
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "false")
public class WebClientAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientAiClient.class);

    private static final String HEADER_INTERNAL_KEY = "X-Internal-Key";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 재시도 대상 AI 응답 코드 (HTTP 5xx 여부가 아니라 코드로 판별). */
    private static final Set<String> RETRYABLE_AI_CODES = Set.of("LLM_UNAVAILABLE", "MODEL_UNAVAILABLE", "DB_UNAVAILABLE");

    private final WebClient http;
    private final ObjectMapper om;
    private final String internalKey;
    private final int maxBatch;
    private final Duration budget;

    public WebClientAiClient(WebClient aiWebClient, AmlProperties props, ObjectMapper om) {
        this.http = aiWebClient;
        this.om = om;
        this.internalKey = props.ai().internalKey();
        this.maxBatch = props.ai().maxBatch();
        this.budget = Duration.ofSeconds(props.ai().timeoutSeconds());
    }

    @Override
    public ScoresResponse scores(long batchRunId, List<AlertPayload> alerts) {
        List<ScoreResult> merged = new ArrayList<>();
        for (int i = 0; i < alerts.size(); i += maxBatch) {       // 500건씩 나눠 보내고 합친다 (규약 6.2)
            List<AlertPayload> chunk = alerts.subList(i, Math.min(i + maxBatch, alerts.size()));
            ObjectNode req = om.createObjectNode();
            req.put("batch_run_id", batchRunId);
            req.set("alerts", om.valueToTree(chunk));
            // TODO(점수 연동): AlertPayload.created_at 등 나머지 필드 최종 확인 (score_request.json). batch_run_id 는 반영됨.
            JsonNode resp = post("/v1/scores", req);
            require(resp, "scores");
            for (JsonNode s : resp.path("scores")) {
                merged.add(toScoreResult(s));
            }
        }
        return new ScoresResponse(merged);
    }

    @Override
    public StrDraftResponse createDraft(StrDraftRequest request) {
        JsonNode r = post("/v1/str/drafts", request);
        require(r, "draft_id");
        return new StrDraftResponse(text(r, "draft_id"), text(r, "generation_id"), text(r, "generator_version"),
                text(r, "produced_at"), r.get("facts"), r.get("narrative"), r);
    }

    @Override
    public VerifyResponse verify(VerifyRequest request) {
        JsonNode r = post("/v1/str/verify", request);
        require(r, "verification_id");
        return new VerifyResponse(text(r, "verification_id"), r.path("passed").asBoolean(false),
                text(r, "verifier_version"), text(r, "produced_at"), r);
    }

    @Override
    public CaseSummaryResponse caseSummary(CaseSummaryRequest request) {
        JsonNode r = post("/v1/cases/summary", request);
        return new CaseSummaryResponse(text(r, "summary"), text(r, "generator_version"), text(r, "produced_at"), r);
    }

    @Override
    public HealthResponse health() {
        JsonNode r = get("/v1/health");
        return new HealthResponse(text(r, "status"), text(r, "model_version"), text(r, "generator_version"));
    }

    // --- 호출 + 재시도 ------------------------------------------------------

    private JsonNode post(String path, Object body) {
        JsonNode json = om.valueToTree(body);   // 요청은 주입된 om(SNAKE_CASE)으로 직렬화해 WebClient 코덱과 무관하게
        return withRetry(path, (requestId, remaining) -> exchange(
                http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_INTERNAL_KEY, internalKey)
                        .header(HEADER_REQUEST_ID, requestId)
                        .bodyValue(json),
                remaining));
    }

    private JsonNode get(String path) {
        return withRetry(path, (requestId, remaining) -> exchange(
                http.get().uri(path)
                        .header(HEADER_INTERNAL_KEY, internalKey)
                        .header(HEADER_REQUEST_ID, requestId),
                remaining));
    }

    private JsonNode withRetry(String path, BiFunction<String, Duration, JsonNode> attempt) {
        String requestId = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + budget.toNanos();
        Classified last = null;
        for (int tries = 1; tries <= 2; tries++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.toMillis() <= 0) break;                 // 남은 시간 없으면 재시도 안 함
            long start = System.nanoTime();
            try {
                JsonNode body = attempt.apply(requestId, remaining);
                log.info("ai call ok path={} request_id={} ms={} try={}", path, requestId, msSince(start), tries);
                return body;
            } catch (RuntimeException e) {
                last = classify(e);
                log.warn("ai call failed path={} request_id={} code={} http={} retryable={} ms={} try={}",
                        path, requestId, last.ex().code(), last.ex().httpStatus(), last.retryable(), msSince(start), tries);
                if (!last.retryable()) break;
            }
        }
        // TODO(SCRUM-18): ai_call_logs 메타데이터 적재. 지금은 위 로그만 (요청·응답 본문 로그 금지 — D5).
        throw last != null ? last.ex() : new AiClientException("AI_TIMEOUT", "AI 서버 응답 시간 초과", Map.of(), 502);
    }

    private JsonNode exchange(WebClient.RequestHeadersSpec<?> spec, Duration remaining) {
        return spec.exchangeToMono(resp -> {
            if (resp.statusCode().is2xxSuccessful()) {
                return resp.bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(badResponse("AI 응답 본문이 비어 있음")));   // 빈 2xx 를 성공으로 위장하지 않음
            }
            int status = resp.statusCode().value();
            return resp.bodyToMono(JsonNode.class).defaultIfEmpty(om.createObjectNode())
                    .flatMap(err -> Mono.error(toClassified(status, err)));
        }).timeout(remaining).block();
    }

    /** 2xx 가 아닐 때 — AI body(code/message)로 (클라이언트 예외 + 재시도 여부)를 만든다. 4xx 는 코드와 무관하게 재시도 안 함. */
    private ClassifiedResponse toClassified(int status, JsonNode body) {
        String code = body.hasNonNull("code") ? body.get("code").asText() : "AI_BAD_RESPONSE";
        String message = body.hasNonNull("message") ? body.get("message").asText() : ("AI 오류 (HTTP " + status + ")");
        if (status == 409 && "GENERATION_IN_PROGRESS".equals(code)) {         // 409 일 때만 "생성 중" 매핑
            return new ClassifiedResponse(new AiClientException("DRAFT_IN_PROGRESS", message, Map.of(), 409), false);
        }
        boolean retryable = status >= 500 && RETRYABLE_AI_CODES.contains(code);   // 5xx + 해당 코드만 재시도 (4xx 는 안 함)
        return new ClassifiedResponse(new AiClientException(code, message, Map.of(), 502), retryable);   // AI 코드 그대로 502
    }

    private ClassifiedResponse badResponse(String message) {
        return new ClassifiedResponse(new AiClientException("AI_BAD_RESPONSE", message, Map.of(), 502), false);
    }

    /**
     * 실패 원인을 (예외 + 재시도 여부)로 분류. 원인 예외를 꺼낸 뒤 ConnectTimeoutException 을 먼저 본다 —
     * ConnectTimeoutException 은 ConnectException 의 하위라 순서가 바뀌면 연결 타임아웃이 연결 거부로 잘못 잡힌다 (SCRUM-17 ③).
     */
    Classified classify(Throwable error) {
        Throwable c = Exceptions.unwrap(error);
        if (c instanceof ClassifiedResponse cr) {                  // 응답 코드+상태로 이미 분류됨
            return new Classified(cr.ex, cr.retryable);
        }
        if (c instanceof AiClientException ae) {                   // 직접 던져진 경우 — 안전하게 재시도 안 함
            return new Classified(ae, false);
        }
        if (c instanceof WebClientRequestException && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof ConnectTimeoutException) {                // ⚠ 반드시 ConnectException 보다 먼저
            return new Classified(new AiClientException("AI_TIMEOUT", "AI 연결 시간 초과", Map.of(), 502), false);
        }
        if (c instanceof ConnectException) {                       // 연결 거부 → 재시도 대상
            return new Classified(new AiClientException("AI_UNAVAILABLE", "AI 서버 응답 없음. 잠시 후 다시 시도", Map.of(), 502), true);
        }
        if (c instanceof ReadTimeoutException || c instanceof TimeoutException) {
            return new Classified(new AiClientException("AI_TIMEOUT", "AI 서버 응답 시간 초과", Map.of(), 502), false);
        }
        return new Classified(new AiClientException("AI_BAD_RESPONSE", "AI 응답 처리 실패: " + c.getClass().getSimpleName(), Map.of(), 502), false);
    }

    private ScoreResult toScoreResult(JsonNode s) {
        Double score = s.hasNonNull("score") ? s.get("score").asDouble() : null;
        return new ScoreResult(text(s, "alert_id"), score, text(s, "score_band"), text(s, "data_quality_status"),
                text(s, "model_version"), text(s, "prediction_id"), text(s, "produced_at"), text(s, "failure_code"), s);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 2xx 인데 필수 필드가 없으면 성공으로 위장하지 않는다 (빈/불완전 응답 → 502). */
    private static void require(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 응답에 " + field + " 없음", Map.of(), 502);
        }
    }

    /** 실패 분류 결과 — 예외와 재시도 여부. 테스트가 직접 검사한다. */
    record Classified(AiClientException ex, boolean retryable) {}

    /** 응답 오류 분류 결과를 재시도 루프로 전달하는 내부 캐리어(HTTP 상태까지 반영해 재시도 여부 결정). */
    private static final class ClassifiedResponse extends RuntimeException {
        final transient AiClientException ex;
        final boolean retryable;
        ClassifiedResponse(AiClientException ex, boolean retryable) {
            super(null, null, false, false);   // 스택트레이스 없이 가볍게
            this.ex = ex;
            this.retryable = retryable;
        }
    }
}
