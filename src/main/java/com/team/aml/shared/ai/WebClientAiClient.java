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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 실제 AI 서버 호출 (aml.ai.mock=false, 규약 v0.2 §6). 유스케이스는 AiClient 인터페이스만 본다.
 *
 * 공통 동작:
 *  - 헤더 X-Internal-Key, X-Request-Id(UUID, 재시도해도 같은 값), X-Budget-Ms(그 시도 때 남은 예산 ms)
 *  - 화면 요청 전체 90초(재시도 포함) = aml.ai.timeout-seconds. 점수는 500건 묶음마다 같은 한도.
 *  - 재시도 1회, 남은 시간 안에서만. 판별은 HTTP 5xx 가 아니라 코드/원인으로:
 *      재시도 O: LLM_UNAVAILABLE·MODEL_UNAVAILABLE·DB_UNAVAILABLE, 또는 연결 거부(ConnectException)
 *      재시도 X: 그 밖의 5xx·4xx·409, 연결 타임아웃(ConnectTimeoutException)·응답 타임아웃
 *  - 실패 매핑: AI 가 준 코드 그대로 502. 연결 거부로 끝나면 AI_UNAVAILABLE 502, 타임아웃은 AI_TIMEOUT 502.
 *  - AI 409 GENERATION_IN_PROGRESS → 409 DRAFT_IN_PROGRESS (재시도 없음).
 *  - AI 404 DRAFT_NOT_FOUND·400 INVALID_REQUEST·401 UNAUTHENTICATED → AI_ 붙여 502 (백엔드 코드와 이름 충돌).
 *
 * HTTP 시도마다 ai_call_logs 에 별도 트랜잭션으로 메타데이터만 기록한다. 요청·응답 본문은 저장하지 않는다 (D5).
 * 2xx 라도 응답 확인이 실패하면 그 시도는 AI_BAD_RESPONSE 실패 행이다(status 는 실제 2xx).
 */
@Component
@ConditionalOnProperty(prefix = "aml.ai", name = "mock", havingValue = "false")
public class WebClientAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientAiClient.class);

    private static final String HEADER_INTERNAL_KEY = "X-Internal-Key";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_BUDGET_MS = "X-Budget-Ms";

    /** 재시도 대상 AI 응답 코드 (HTTP 5xx 여부가 아니라 코드로 판별). */
    private static final Set<String> RETRYABLE_AI_CODES = Set.of("LLM_UNAVAILABLE", "MODEL_UNAVAILABLE", "DB_UNAVAILABLE");

    /**
     * "HTTP 상태 + AI 코드" → 프론트에 줄 코드. 백엔드 자체 404 DRAFT_NOT_FOUND·400 INVALID_REQUEST·401 UNAUTHENTICATED 와
     * 이름이 같아 뜻이 섞이지 않게 AI_ 를 붙인다 (502). 03_shared §2 (SCRUM-128).
     */
    private static final Map<String, String> RENAMED_AI_CODES = Map.of(
            "404 DRAFT_NOT_FOUND", "AI_DRAFT_NOT_FOUND",
            "400 INVALID_REQUEST", "AI_INVALID_REQUEST",
            "401 UNAUTHENTICATED", "AI_UNAUTHENTICATED");

    private final WebClient http;
    private final AiCallLogWriter writer;
    private final ObjectMapper om;
    private final String internalKey;
    private final int maxBatch;
    private final Duration budget;

    public WebClientAiClient(WebClient aiWebClient, AmlProperties props, ObjectMapper om, AiCallLogWriter writer) {
        this.http = aiWebClient;
        this.writer = writer;
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
            merged.addAll(post("/v1/scores", new ScoresRequest(batchRunId, chunk), "BATCH_RUN", String.valueOf(batchRunId),
                    resp -> {
                        List<ScoreResult> items = readScoreBatch(om, resp);
                        requireSameBatch(batchRunId, chunk, resp, items);
                        return items;
                    }));
        }
        return new ScoresResponse(merged);
    }

    /**
     * 점수 응답 한 묶음 → 항목 목록 (규약 §3, SCRUM-128). 맨 위 `scoring_meta`·`produced_at` 을 항목마다 붙인다
     * (같은 묶음은 같은 `ScoringMeta` 객체). 칸 확인은 AI 계약(`scores.py`)과 같은 기준 + `threshold` 0~1 (SCRUM-204):
     * `scoring_meta` 세 버전 문자열(빈 값 허용)·`latency_ms` 0 이상 정수, `threshold` 숫자 0~1,
     * `score` 는 FAILED 면 null·그 밖엔 숫자 0~1, 거래 ID 원소는 1 이상 정수. 틀리면 AI_BAD_RESPONSE 502 — 메시지엔 칸 이름만.
     * withRetry 안·기록 전에 불려 실패 행으로 남는다. 계약 예시 테스트가 직접 부른다.
     */
    static List<ScoreResult> readScoreBatch(ObjectMapper om, JsonNode body) {
        require(body, "scores");
        require(body, "scoring_meta");
        require(body, "produced_at");
        if (!body.get("scores").isArray()) throw badScore("scores");
        ScoringMeta meta = readMeta(body.get("scoring_meta"));
        String producedAt = body.get("produced_at").asText();
        List<ScoreResult> out = new ArrayList<>();
        for (JsonNode s : body.get("scores")) {
            String status = text(s, "data_quality_status");
            out.add(new ScoreResult(text(s, "alert_id"), text(s, "prediction_id"), score(s, status),
                    unitNumber(s.get("threshold"), "threshold"), status, text(s, "failure_code"),
                    s.hasNonNull("reasons") ? s.get("reasons") : om.createArrayNode(),
                    longs(s, "evidence_transaction_ids"), longs(s, "context_transaction_ids"),
                    meta, producedAt, s));
        }
        return out;
    }

    private static ScoringMeta readMeta(JsonNode m) {
        if (!m.isObject()) throw badScore("scoring_meta");
        JsonNode latency = m.get("latency_ms");
        if (latency == null || !latency.isIntegralNumber() || !latency.canConvertToInt() || latency.intValue() < 0) {
            throw badScore("scoring_meta.latency_ms");
        }
        return new ScoringMeta(metaText(m, "model_version"), metaText(m, "feature_version"),
                metaText(m, "server_version"), latency.intValue());
    }

    private static String metaText(JsonNode m, String field) {
        JsonNode v = m.get(field);
        if (v == null || !v.isTextual()) throw badScore("scoring_meta." + field);
        return v.textValue();
    }

    /** FAILED 면 null 이어야 하고, 그 밖엔 숫자 0~1 이 있어야 한다 (AI ScoreItem 규칙). */
    private static Double score(JsonNode s, String status) {
        boolean failed = "FAILED".equals(status);
        if (!s.hasNonNull("score")) {
            if (failed) return null;
            throw badScore("score");
        }
        if (failed) throw badScore("score");
        return unitNumber(s.get("score"), "score");
    }

    private static double unitNumber(JsonNode v, String field) {
        if (v == null || !v.isNumber() || v.doubleValue() < 0 || v.doubleValue() > 1) throw badScore(field);
        return v.doubleValue();
    }

    private static List<Long> longs(JsonNode s, String field) {
        if (!s.hasNonNull(field)) return List.of();
        JsonNode arr = s.get(field);
        if (!arr.isArray()) throw badScore(field);
        List<Long> out = new ArrayList<>(arr.size());
        for (JsonNode v : arr) {
            if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1) throw badScore(field);   // AI TransactionId ge=1
            out.add(v.longValue());
        }
        return List.copyOf(out);
    }

    private static AiClientException badScore(String field) {
        return new AiClientException("AI_BAD_RESPONSE", "AI 점수 응답 확인 실패: " + field, Map.of(), 502);
    }

    /**
     * 묶음 응답이 보낸 묶음과 같은지 — `batch_run_id` 같고, `alert_id` 가 보낸 경보와 빠짐·남음·중복 없이 같다.
     * 다르면 AI_BAD_RESPONSE 502 (#60 `draft_id` 대조와 같은 원칙, 08_decisions 11/6).
     */
    private static void requireSameBatch(long batchRunId, List<AlertPayload> sent, JsonNode resp, List<ScoreResult> items) {
        JsonNode id = resp.get("batch_run_id");
        if (id == null || !id.canConvertToLong() || id.asLong() != batchRunId) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 점수 응답 batch_run_id 가 요청과 다름", Map.of(), 502);
        }
        List<String> want = new ArrayList<>(sent.stream().map(AlertPayload::alertId).toList());
        List<String> got = new ArrayList<>(items.stream().map(ScoreResult::alertId).toList());
        want.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        got.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
        if (!want.equals(got)) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 점수 응답 alert_id 가 보낸 경보와 다름", Map.of(), 502);
        }
    }

    @Override
    public StrDraftResponse createDraft(StrDraftRequest request) {
        return post("/v1/str/drafts", request, "DRAFT", request.draftId(),
                r -> readRecord(r, "draft_id", StrDraftResponse.class, "AI 초안 응답 파싱 실패"));
    }

    @Override
    public VerifyResponse verify(String draftId, VerifyRequest request) {
        return post("/v1/str/verify", request, "DRAFT", draftId, r -> {
            requireVerifyResponse(request, r);
            return readRecord(r, "verification_id", VerifyResponse.class, "AI 검증 응답 파싱 실패");
        });
    }

    private static final Set<String> VERIFY_STATUSES = Set.of("PASSED", "CODE_FAILED", "LLM_FAILED", "VERIFY_UNAVAILABLE");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * 검증 응답 칸 확인 + 보낸 값 대조 (규약 §5.3, #95 리뷰). 저장(VerificationResult)에 쓰는 칸이 모두 있고 모양이 맞는지,
     * `generation_id`·`revision_no` 가 보낸 값과 같은지. 틀리면 AI_BAD_RESPONSE 502 — withRetry 안·기록 전에 불려
     * 실패 행(status 2xx)으로 남는다(점수 `readScoreBatch`·`requireSameBatch` 와 같은 원칙, SCRUM-128). 메시지엔 칸 이름만.
     */
    static void requireVerifyResponse(VerifyRequest sent, JsonNode r) {
        for (String field : List.of("verification_id", "generation_id", "revision_no", "narrative_sha256",
                "status", "verifier_version", "produced_at")) {
            require(r, field);
        }
        if (!VERIFY_STATUSES.contains(r.get("status").asText())) throw badVerify("status");
        if (!SHA256_HEX.matcher(r.get("narrative_sha256").asText()).matches()) throw badVerify("narrative_sha256");
        String producedAt = r.get("produced_at").asText();
        try {
            if (!producedAt.endsWith("Z")) throw badVerify("produced_at");   // UTC Z 만 (규약 1.4)
            Instant.parse(producedAt);
        } catch (DateTimeParseException e) {
            throw badVerify("produced_at");
        }
        if (!r.get("generation_id").asText().equals(sent.generationId())) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 검증 응답 generation_id 가 요청과 다름", Map.of(), 502);
        }
        JsonNode revisionNo = r.get("revision_no");
        if (!revisionNo.isIntegralNumber() || !revisionNo.canConvertToInt()
                || !Integer.valueOf(revisionNo.intValue()).equals(sent.revisionNo())) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 검증 응답 revision_no 가 요청과 다름", Map.of(), 502);
        }
    }

    private static AiClientException badVerify(String field) {
        return new AiClientException("AI_BAD_RESPONSE", "AI 검증 응답 확인 실패: " + field, Map.of(), 502);
    }

    /** 필수 칸 확인 + `raw` 넣어 record 로. 실패는 AI_BAD_RESPONSE 502 — withRetry 안에서 불려 실패 행으로 남는다. */
    private <T> T readRecord(JsonNode r, String requiredField, Class<T> type, String parseFailMessage) {
        require(r, requiredField);
        try {
            return om.treeToValue(injectRaw(r), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiClientException("AI_BAD_RESPONSE", parseFailMessage, Map.of(), 502);
        }
    }

    /** `raw` 칸에 응답 전체를 넣어 record 로 역직렬화. 원문 전체 보존(원칙 4, SCRUM-151). */
    private JsonNode injectRaw(JsonNode body) {
        if (!(body instanceof ObjectNode obj)) return body;
        ObjectNode copy = obj.deepCopy();
        copy.set("raw", obj);
        return copy;
    }

    @Override
    public CaseSummaryResponse caseSummary(CaseSummaryRequest request) {
        return post("/v1/cases/summary", request, "CASE", request.caseNo(),
                r -> readRecord(r, "case_no", CaseSummaryResponse.class, "AI 요약 응답 파싱 실패"));
    }

    @Override
    public HealthResponse health() {
        return get("/v1/health", r -> new HealthResponse(text(r, "status"), text(r, "db"), text(r, "llm"),
                text(r, "server_version"), text(r, "model_version"), text(r, "feature_version"), text(r, "retrieval_version")));
    }

    // --- 호출 + 재시도 ------------------------------------------------------

    private <T> T post(String path, Object body, String targetType, String targetId, Function<JsonNode, T> reader) {
        JsonNode json = om.valueToTree(body);   // 요청은 주입된 om(SNAKE_CASE)으로 직렬화해 WebClient 코덱과 무관하게
        return withRetry(path, targetType, targetId, (requestId, remaining) -> exchange(
                http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_INTERNAL_KEY, internalKey)
                        .header(HEADER_REQUEST_ID, requestId)
                        .header(HEADER_BUDGET_MS, String.valueOf(remaining.toMillis()))
                        .bodyValue(json),
                remaining), reader);
    }

    private <T> T get(String path, Function<JsonNode, T> reader) {
        return withRetry(path, null, null, (requestId, remaining) -> exchange(
                http.get().uri(path)
                        .header(HEADER_INTERNAL_KEY, internalKey)
                        .header(HEADER_REQUEST_ID, requestId)
                        .header(HEADER_BUDGET_MS, String.valueOf(remaining.toMillis())),
                remaining), reader);
    }

    /**
     * 시도마다 한 행. 2xx 면 reader(필수 칸·변환·묶음 대조)를 **기록 전에** 돌려, 실패하면 같은 시도를
     * status=실제 2xx · AI_BAD_RESPONSE 실패 행으로 남기고 재시도 없이 던진다 (형식 오류는 재시도 대상 아님, SCRUM-128).
     */
    private <T> T withRetry(String path, String targetType, String targetId,
                            BiFunction<String, Duration, Response> attempt, Function<JsonNode, T> reader) {
        String requestId = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + budget.toNanos();
        Classified last = null;
        for (int tries = 1; tries <= 2; tries++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.toMillis() <= 0) break;                 // 남은 시간 없으면 재시도 안 함
            long start = System.nanoTime();
            Response response;
            try {
                response = attempt.apply(requestId, remaining);
            } catch (RuntimeException e) {
                last = classify(e);
                Integer status = e instanceof ClassifiedResponse cr ? cr.aiStatus : null;
                recordAttempt(path, requestId, tries, status, last.ex().code(), start, null, targetType, targetId);
                log.warn("ai call failed path={} request_id={} code={} http={} retryable={} ms={} try={}",
                        path, requestId, last.ex().code(), last.ex().httpStatus(), last.retryable(), msSince(start), tries);
                if (!last.retryable()) break;
                continue;
            }
            T value;
            try {
                value = reader.apply(response.body());
            } catch (RuntimeException e) {
                AiClientException bad = classify(e).ex();          // AiClientException 은 그대로, 그 밖은 AI_BAD_RESPONSE
                recordAttempt(path, requestId, tries, response.status(), bad.code(), start, response.body(), targetType, targetId);
                log.warn("ai response rejected path={} request_id={} code={} http={} ms={} try={}",
                        path, requestId, bad.code(), response.status(), msSince(start), tries);
                throw bad;
            }
            recordAttempt(path, requestId, tries, response.status(), null, start, response.body(), targetType, targetId);
            log.info("ai call ok path={} request_id={} ms={} try={}", path, requestId, msSince(start), tries);
            return value;
        }
        throw last != null ? last.ex() : new AiClientException("AI_TIMEOUT", "AI 서버 응답 시간 초과", Map.of(), 502);
    }

    private Response exchange(WebClient.RequestHeadersSpec<?> spec, Duration remaining) {
        return spec.exchangeToMono(resp -> {
            int status = resp.statusCode().value();
            if (resp.statusCode().is2xxSuccessful()) {
                return resp.bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(badResponse("AI 응답 본문이 비어 있음")))
                        .map(body -> new Response(status, body))
                        .onErrorMap(e -> withStatus(status, e));   // 빈 2xx 를 성공으로 위장하지 않음
            }
            return resp.bodyToMono(JsonNode.class).defaultIfEmpty(om.createObjectNode())
                    .<Response>flatMap(err -> Mono.error(toClassified(status, err)))
                    .onErrorMap(e -> withStatus(status, e));
        }).timeout(remaining).block();
    }

    /** 2xx 가 아닐 때 — AI body(code/message)로 (클라이언트 예외 + 재시도 여부)를 만든다. 4xx 는 코드와 무관하게 재시도 안 함. */
    private ClassifiedResponse toClassified(int status, JsonNode body) {
        String code = body.hasNonNull("code") ? body.get("code").asText() : "AI_BAD_RESPONSE";
        String message = body.hasNonNull("message") ? body.get("message").asText() : ("AI 오류 (HTTP " + status + ")");
        if (status == 409 && "GENERATION_IN_PROGRESS".equals(code)) {         // 409 일 때만 "생성 중" 매핑
            return new ClassifiedResponse(new AiClientException("DRAFT_IN_PROGRESS", message, Map.of(), 409), false, status);
        }
        String renamed = RENAMED_AI_CODES.get(status + " " + code);          // 백엔드 자체 코드와 이름이 같은 AI 4xx
        if (renamed != null) {
            return new ClassifiedResponse(new AiClientException(renamed, message, Map.of(), 502), false, status);
        }
        boolean retryable = status >= 500 && RETRYABLE_AI_CODES.contains(code);   // 5xx + 해당 코드만 재시도 (4xx 는 안 함)
        return new ClassifiedResponse(new AiClientException(code, message, Map.of(), 502), retryable, status);   // AI 코드 그대로 502
    }

    private ClassifiedResponse badResponse(String message) {
        return new ClassifiedResponse(new AiClientException("AI_BAD_RESPONSE", message, Map.of(), 502), false, null);
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

    /** block() 이후 호출 스레드에서만 저장. 프록시의 flush·commit 실패까지 격리한다. */
    private void recordAttempt(String path, String requestId, int attempt, Integer status, String errorCode,
                               long start, JsonNode body, String targetType, String targetId) {
        int durationMs = (int) Math.min(msSince(start), Integer.MAX_VALUE);
        try {
            String version = body == null ? null : switch (path) {
                case "/v1/scores" -> text(body.path("scoring_meta"), "model_version");
                case "/v1/str/drafts" -> text(body.path("generation_meta"), "prompt_version");
                case "/v1/str/verify" -> text(body, "verifier_version");
                case "/v1/cases/summary" -> text(body.path("generation_meta"), "prompt_version");
                default -> null;
            };
            writer.write(AiCallLog.create(path, requestId, attempt, status, errorCode, durationMs,
                    version, text(body, "generation_id"), targetType, targetId));
        } catch (RuntimeException e) {
            log.warn("ai call log failed path={} request_id={} try={}", path, requestId, attempt);
        }
    }

    private record Response(int status, JsonNode body) {}

    private ClassifiedResponse withStatus(int status, Throwable error) {
        Classified c = classify(error);
        Integer aiStatus = error instanceof ReadTimeoutException || error instanceof TimeoutException ? null : status;
        return new ClassifiedResponse(c.ex(), c.retryable(), aiStatus);
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
        final Integer aiStatus;
        ClassifiedResponse(AiClientException ex, boolean retryable, Integer aiStatus) {
            super(null, null, false, false);   // 스택트레이스 없이 가볍게
            this.ex = ex;
            this.retryable = retryable;
            this.aiStatus = aiStatus;
        }
    }
}
