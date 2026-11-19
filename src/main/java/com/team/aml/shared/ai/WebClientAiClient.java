package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.ai.AiDtos.*;
import com.team.aml.shared.config.AmlProperties;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.unix.Errors;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.Normalizer;
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
 *  - 헤더 X-Internal-Key, X-Request-Id(MDC request_id, 없거나 공백이면 UUID, 재시도해도 같은 값), X-Budget-Ms(그 시도 때 남은 예산 ms)
 *  - 화면 요청 전체 90초(재시도 포함) = aml.ai.timeout-seconds. 점수는 500건 묶음마다 같은 한도.
 *    질문 답변(/v1/cases/ask)만 전체 30초 = aml.ai.ask-timeout-seconds (규약 6.2, SCRUM-291). 한도는 호출마다 따로 잰다.
 *  - 재시도 1회, 남은 시간 안에서만. 판별은 HTTP 5xx 가 아니라 코드/원인으로:
 *      재시도 O: LLM_UNAVAILABLE·MODEL_UNAVAILABLE·DB_UNAVAILABLE, 또는 연결 단계 실패(CONNECT_STAGE_FAILURES — 연결 거부·이름 조회 실패·경로 없음)
 *      재시도 X: 그 밖의 5xx·4xx·409, 연결 타임아웃(ConnectTimeoutException)·응답 타임아웃, 보낸 뒤 끊김(CLOSED_AFTER_SEND)
 *  - 실패 매핑: AI 가 준 코드 그대로 502. 연결 실패(단계 실패·보낸 뒤 끊김)는 AI_UNAVAILABLE 502, 타임아웃은 AI_TIMEOUT 502,
 *    그 밖은 AI_BAD_RESPONSE 502 "AI 응답 처리 실패"(예외 클래스 이름은 로그 cause= 에만, SCRUM-229).
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
     * 연결 단계 실패 — 요청이 AI 에 닿기 전에 끝남. 다시 보내도 중복 생성이 없어 AI_UNAVAILABLE + 재시도 1회 (SCRUM-229).
     * ConnectException 은 연결 거부(netty AnnotatedConnectException 포함). 하위인 ConnectTimeoutException 은 classify 가 먼저 거른다.
     */
    private static final List<Class<? extends Throwable>> CONNECT_STAGE_FAILURES =
            List.of(ConnectException.class, UnknownHostException.class, NoRouteToHostException.class);

    /**
     * 보낸 뒤(또는 보냈는지 모를 때) 연결 끊김 — AI 가 이미 받아 생성 중일 수 있어 AI_UNAVAILABLE + 재시도 안 함 (SCRUM-229).
     * PrematureCloseException: 응답 전·2xx 본문 중 끊김. 연결 리셋은 NIO(맥)는 SocketException, epoll(리눅스 컨테이너)은 NativeIoException.
     */
    private static final List<Class<? extends Throwable>> CLOSED_AFTER_SEND =
            List.of(PrematureCloseException.class, SocketException.class, Errors.NativeIoException.class);

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
    private final Duration askBudget;

    public WebClientAiClient(WebClient aiWebClient, AmlProperties props, ObjectMapper om, AiCallLogWriter writer) {
        this.http = aiWebClient;
        this.writer = writer;
        this.om = om;
        this.internalKey = props.ai().internalKey();
        this.maxBatch = props.ai().maxBatch();
        this.budget = Duration.ofSeconds(props.ai().timeoutSeconds());
        this.askBudget = Duration.ofSeconds(props.ai().askTimeoutSeconds());
    }

    @Override
    public ScoresResponse scores(long batchRunId, List<AlertPayload> alerts) {
        List<ScoreResult> merged = new ArrayList<>();
        for (int i = 0; i < alerts.size(); i += maxBatch) {       // 500건씩 나눠 보내고 합친다 (규약 6.2)
            List<AlertPayload> chunk = alerts.subList(i, Math.min(i + maxBatch, alerts.size()));
            merged.addAll(post("/v1/scores", new ScoresRequest(batchRunId, chunk), "BATCH_RUN", String.valueOf(batchRunId), budget,
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
        return post("/v1/str/drafts", request, "DRAFT", request.draftId(), budget,
                r -> readRecord(r, "draft_id", StrDraftResponse.class, "AI 초안 응답 파싱 실패"));
    }

    @Override
    public VerifyResponse verify(String draftId, VerifyRequest request) {
        return post("/v1/str/verify", request, "DRAFT", draftId, budget, r -> {
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
        return post("/v1/cases/summary", request, "CASE", request.caseNo(), budget,
                r -> readRecord(r, "case_no", CaseSummaryResponse.class, "AI 요약 응답 파싱 실패"));
    }

    /** 질문 답변 — 이 호출만 askBudget(30초). 응답 확인은 기록 전에(실패면 실패 행·재시도 없음). SCRUM-291 */
    @Override
    public AskResponse ask(AskRequest request) {
        return post("/v1/cases/ask", request, "CASE", request.caseNo(), askBudget, r -> {
            requireAskResponse(request, r);
            return readRecord(r, "case_no", AskResponse.class, "AI 질문 답변 응답 파싱 실패");
        });
    }

    /** AI `ask.py` 의 상한 — 답 400(NFC 뒤 UTF-16), 묶음 3개. */
    static final int ASK_ANSWER_MAX = 400;
    static final int ASK_BUNDLES_MAX = 3;
    /** `ai_call_logs.ai_version` 칸 길이(V004_2 varchar(64), `AiCallLog`). 넘거나 문자열이 아니면 null 로 기록. */
    static final int AI_VERSION_MAX = 64;
    /** AI `verify.py` `CodeCheckName`·`CheckReason`. */
    private static final Set<String> CODE_CHECK_NAMES = Set.of("AMOUNT_MATCH", "DATE_MATCH", "COUNT_MATCH",
            "ACCOUNT_ID_MATCH", "EVIDENCE_IN_SCOPE", "OPINION_PRESENT");
    private static final Set<String> CODE_CHECK_REASONS = Set.of("MISMATCH", "UNPARSED", "OUT_OF_SCOPE", "MISSING");
    /** AI `narrative.py` `Section = SectionV1 | SectionV2` — str `StrSection` 과 같은 이름(shared 라 참조하지 않음). */
    private static final Set<String> NARRATIVE_SECTIONS = Set.of(
            "SUMMARY", "WHO", "WHAT_WHEN_WHERE", "HOW", "WHY_SUSPICIOUS", "CONCLUSION",
            "SUBJECT", "DATE", "PLACE", "INSTRUMENT", "METHOD", "GROUNDS", "OPINION");

    /**
     * 질문 답변 응답 확인 (규약 5.4 = AI `ask.py` `AskResponse`·`AskGenerationMeta`·`CodeCheck` 규칙 + 보낸 값 대조, SCRUM-291).
     * 틀리면 AI_BAD_RESPONSE 502, 메시지엔 칸 이름만(질문·답 문장 없음). AI 보다 더 보는 곳: `case_no` 대조,
     * `evidence_ids` ⊆ 보낸 근거, `produced_at` 은 Z 만. 안 보는 곳: 모르는 칸(원칙 4 — 받아 둠).
     */
    static void requireAskResponse(AskRequest sent, JsonNode r) {
        for (String field : List.of("produced_at", "case_no", "generation_id", "status", "generation_meta")) {
            if (!r.hasNonNull(field)) throw badAsk(field);
        }
        for (String field : List.of("case_no", "generation_id")) {
            if (!r.get(field).isTextual()) throw badAsk(field);
        }
        for (String field : List.of("cites", "evidence_ids", "code_checks")) {
            if (!r.path(field).isArray()) throw badAsk(field);
        }
        AskStatus status = askEnum(AskStatus.class, r.get("status"), "status");
        requireUtcZ(r.get("produced_at"), "produced_at");
        if (!r.get("case_no").textValue().equals(sent.caseNo())) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 질문 답변 응답 case_no 가 요청과 다름", Map.of(), 502);
        }

        boolean outOfScope = status == AskStatus.OUT_OF_SCOPE;
        if (outOfScope) {
            if (r.hasNonNull("kind")) throw badAsk("kind");
            askEnum(OutOfScopeReason.class, r.get("out_of_scope_reason"), "out_of_scope_reason");
        } else {
            askEnum(AskKind.class, r.get("kind"), "kind");
            if (r.hasNonNull("out_of_scope_reason")) throw badAsk("out_of_scope_reason");
        }

        JsonNode answer = r.get("answer");
        if (answer != null && !answer.isNull()) {   // ask.py: 공백만 X, NFC 뒤 UTF-16 ≤400, 앞뒤 공백은 그대로 셈
            if (!answer.isTextual() || isBlankLikePython(answer.textValue())
                    || Normalizer.normalize(answer.textValue(), Normalizer.Form.NFC).length() > ASK_ANSWER_MAX) {
                throw badAsk("answer");
            }
        }
        requireDistinctNonBlankStrings(r.get("cites"), "cites", Integer.MAX_VALUE);
        if (status == AskStatus.ANSWERED) {
            if (answer == null || answer.isNull()) throw badAsk("answer");
            if (r.get("cites").isEmpty()) throw badAsk("cites");
        } else {
            if (answer != null && !answer.isNull()) throw badAsk("answer");
            if (!r.get("cites").isEmpty()) throw badAsk("cites");
            if (!r.get("evidence_ids").isEmpty()) throw badAsk("evidence_ids");
        }

        Set<Long> sentEvidence = sent.evidenceTransactionIds() == null ? Set.of() : Set.copyOf(sent.evidenceTransactionIds());
        for (JsonNode id : r.get("evidence_ids")) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 1
                    || !sentEvidence.contains(id.longValue())) {
                throw badAsk("evidence_ids");
            }
        }

        boolean mismatch = false;
        for (JsonNode check : r.get("code_checks")) {
            mismatch |= !requireCodeCheck(check);
        }
        boolean checksOk = switch (status) {
            case ANSWERED -> !mismatch;
            case UNVERIFIED -> mismatch;
            case NO_DATA, OUT_OF_SCOPE -> r.get("code_checks").isEmpty();
        };
        if (!checksOk) throw badAsk("code_checks");

        requireAskMeta(r.get("generation_meta"), outOfScope);
    }

    /** AI `CodeCheck` 한 개 — check 6종, section 13종|null, draft_value·source_value 칸 있음(문자열|null), match 참거짓, reason 은 match=false 일 때만(4종). @return match */
    private static boolean requireCodeCheck(JsonNode c) {
        if (!c.isObject()) throw badAsk("code_checks");
        JsonNode check = c.get("check");
        if (check == null || !check.isTextual() || !CODE_CHECK_NAMES.contains(check.textValue())) throw badAsk("code_checks");
        JsonNode section = c.get("section");
        if (section != null && !section.isNull()
                && (!section.isTextual() || !NARRATIVE_SECTIONS.contains(section.textValue()))) {
            throw badAsk("code_checks");
        }
        for (String field : List.of("draft_value", "source_value")) {
            JsonNode v = c.get(field);
            if (v == null || !(v.isNull() || v.isTextual())) throw badAsk("code_checks");
        }
        JsonNode match = c.get("match");
        if (match == null || !match.isBoolean()) throw badAsk("code_checks");
        JsonNode reason = c.get("reason");
        boolean hasReason = reason != null && !reason.isNull();
        if (hasReason && (!reason.isTextual() || !CODE_CHECK_REASONS.contains(reason.textValue()))) throw badAsk("code_checks");
        if (match.booleanValue() == hasReason) throw badAsk("code_checks");   // match=true 면 reason 없음, false 면 있음
        return match.booleanValue();
    }

    /** AI `AskGenerationMeta` — 칸 8개 타입 + 상태 짝(OUT_OF_SCOPE 면 prompt_version null·bundles [], 그 밖은 값·1~3개). */
    private static void requireAskMeta(JsonNode m, boolean outOfScope) {
        if (!m.isObject()) throw badAsk("generation_meta");
        for (String field : List.of("llm_model", "plan_prompt_version", "server_version")) {
            if (!m.path(field).isTextual()) throw badAsk("generation_meta." + field);
        }
        for (String field : List.of("tokens_in", "tokens_out", "latency_ms")) {
            JsonNode v = m.get(field);
            if (v == null || !v.isIntegralNumber()) throw badAsk("generation_meta." + field);
        }
        JsonNode promptVersion = m.get("prompt_version");   // 칸은 있어야 함(값 null 허용)
        boolean promptOk = promptVersion != null
                && (outOfScope ? promptVersion.isNull() : promptVersion.isTextual());
        if (!promptOk) throw badAsk("generation_meta.prompt_version");
        JsonNode bundles = m.get("bundles");
        if (bundles == null || !bundles.isArray()) throw badAsk("generation_meta.bundles");
        requireDistinctNonBlankStrings(bundles, "generation_meta.bundles", ASK_BUNDLES_MAX);
        if (outOfScope != bundles.isEmpty()) throw badAsk("generation_meta.bundles");
    }

    /** ask.py `cites`·`bundles` — 원소는 문자열, 앞뒤 공백을 뺀 값이 비지 않고 서로 다름(AI 가 공백을 자른 뒤 비교). */
    private static void requireDistinctNonBlankStrings(JsonNode arr, String field, int max) {
        if (arr.size() > max) throw badAsk(field);
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode v : arr) {
            if (!v.isTextual()) throw badAsk(field);
            String stripped = stripLikePython(v.textValue());
            if (stripped.isEmpty() || !seen.add(stripped)) throw badAsk(field);
        }
    }

    /** 파이썬 `str.strip()` 처럼 — 공백 판정은 {@link #isPythonSpace} (pydantic `str_strip_whitespace` 도 `strip()`). */
    private static String stripLikePython(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPythonSpace(s.charAt(start))) start++;
        while (end > start && isPythonSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isBlankLikePython(String s) {
        return stripLikePython(s).isEmpty();
    }

    /**
     * 파이썬 `str.isspace()` 가 참인 글자 29개 그대로 (AI 서버 Python 3.12 에서 0~U+10FFFF 전부 돌려 확인, SCRUM-291).
     * JDK 의 isWhitespace·isSpaceChar 조합은 U+0085(NEL)를 놓쳐서 쓰지 않는다. 전부 BMP 라 char 로 충분.
     * (U+000A·U+000D 는 자바 소스의 유니코드 이스케이프가 줄 바꿈이 돼서 16진 숫자로 적는다.)
     */
    static boolean isPythonSpace(char c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20)
                || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** 문자열이고 enum 이름 중 하나여야 한다(모르는 값·null 은 실패). */
    private static <E extends Enum<E>> E askEnum(Class<E> type, JsonNode v, String field) {
        if (v == null || !v.isTextual()) throw badAsk(field);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(v.textValue())) return e;
        }
        throw badAsk(field);
    }

    private static void requireUtcZ(JsonNode v, String field) {
        try {
            if (!v.isTextual() || !v.textValue().endsWith("Z")) throw badAsk(field);   // UTC Z 만 (규약 1.4)
            Instant.parse(v.textValue());
        } catch (DateTimeParseException e) {
            throw badAsk(field);
        }
    }

    private static AiClientException badAsk(String field) {
        return new AiClientException("AI_BAD_RESPONSE", "AI 질문 답변 응답 확인 실패: " + field, Map.of(), 502);
    }

    @Override
    public HealthResponse health() {
        return get("/v1/health", r -> new HealthResponse(text(r, "status"), text(r, "db"), text(r, "llm"),
                text(r, "server_version"), text(r, "model_version"), text(r, "feature_version"), text(r, "retrieval_version")));
    }

    // --- 호출 + 재시도 ------------------------------------------------------

    /** {@code budget} = 이 호출 전체 한도(재시도 포함). 질문 답변만 askBudget, 나머지는 budget. */
    private <T> T post(String path, Object body, String targetType, String targetId, Duration budget,
                       Function<JsonNode, T> reader) {
        JsonNode json = om.valueToTree(body);   // 요청은 주입된 om(SNAKE_CASE)으로 직렬화해 WebClient 코덱과 무관하게
        return withRetry(path, targetType, targetId, budget, (requestId, remaining) -> exchange(
                http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_INTERNAL_KEY, internalKey)
                        .header(HEADER_REQUEST_ID, requestId)
                        .header(HEADER_BUDGET_MS, String.valueOf(remaining.toMillis()))
                        .bodyValue(json),
                remaining), reader);
    }

    private <T> T get(String path, Function<JsonNode, T> reader) {
        return withRetry(path, null, null, budget, (requestId, remaining) -> exchange(
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
    private <T> T withRetry(String path, String targetType, String targetId, Duration budget,
                            BiFunction<String, Duration, Response> attempt, Function<JsonNode, T> reader) {
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
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
                log.warn("ai call failed path={} request_id={} code={} http={} retryable={} cause={} ms={} try={}",
                        path, requestId, last.ex().code(), last.ex().httpStatus(), last.retryable(), last.cause(), msSince(start), tries);
                if (!last.retryable()) break;
                continue;
            }
            T value;
            try {
                value = reader.apply(response.body());
            } catch (RuntimeException e) {
                Classified rejected = classify(e);                  // AiClientException 은 그대로, 그 밖은 AI_BAD_RESPONSE
                AiClientException bad = rejected.ex();
                recordAttempt(path, requestId, tries, response.status(), bad.code(), start, response.body(), targetType, targetId);
                log.warn("ai response rejected path={} request_id={} code={} http={} cause={} ms={} try={}",
                        path, requestId, bad.code(), response.status(), rejected.cause(), msSince(start), tries);
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
            return new ClassifiedResponse(new AiClientException("DRAFT_IN_PROGRESS", message, Map.of(), 409), false, status, null);
        }
        String renamed = RENAMED_AI_CODES.get(status + " " + code);          // 백엔드 자체 코드와 이름이 같은 AI 4xx
        if (renamed != null) {
            return new ClassifiedResponse(new AiClientException(renamed, message, Map.of(), 502), false, status, null);
        }
        boolean retryable = status >= 500 && RETRYABLE_AI_CODES.contains(code);   // 5xx + 해당 코드만 재시도 (4xx 는 안 함)
        return new ClassifiedResponse(new AiClientException(code, message, Map.of(), 502), retryable, status, null);   // AI 코드 그대로 502
    }

    private ClassifiedResponse badResponse(String message) {
        return new ClassifiedResponse(new AiClientException("AI_BAD_RESPONSE", message, Map.of(), 502), false, null, null);
    }

    /**
     * 실패 원인을 (예외 + 재시도 여부)로 분류. 원인 예외를 꺼낸 뒤 ConnectTimeoutException 을 먼저 본다 —
     * ConnectTimeoutException 은 ConnectException 의 하위라 순서가 바뀌면 연결 타임아웃이 연결 거부로 잘못 잡힌다 (SCRUM-17 ③).
     */
    Classified classify(Throwable error) {
        Throwable c = Exceptions.unwrap(error);
        if (c instanceof ClassifiedResponse cr) {                  // 응답 코드+상태로 이미 분류됨
            return new Classified(cr.ex, cr.retryable, cr.cause);
        }
        if (c instanceof AiClientException ae) {                   // 직접 던져진 경우 — 안전하게 재시도 안 함
            return new Classified(ae, false, null);
        }
        if (c instanceof WebClientRequestException && c.getCause() != null) {
            c = c.getCause();
        }
        String cause = c.getClass().getSimpleName();               // 로그 cause= 칸에만 — 응답 메시지엔 넣지 않음
        if (c instanceof ConnectTimeoutException) {                // ⚠ 반드시 ConnectException 보다 먼저
            return new Classified(new AiClientException("AI_TIMEOUT", "AI 연결 시간 초과", Map.of(), 502), false, cause);
        }
        if (isAny(c, CONNECT_STAGE_FAILURES)) {                   // 요청이 AI 에 안 닿음 → 재시도 대상
            return new Classified(unavailable(), true, cause);
        }
        if (c instanceof ReadTimeoutException || c instanceof TimeoutException) {
            return new Classified(new AiClientException("AI_TIMEOUT", "AI 서버 응답 시간 초과", Map.of(), 502), false, cause);
        }
        if (isAny(c, CLOSED_AFTER_SEND)) {                        // ⚠ CONNECT_STAGE_FAILURES 뒤 — SocketException 이 둘의 부모
            return new Classified(unavailable(), false, cause);
        }
        return new Classified(new AiClientException("AI_BAD_RESPONSE", "AI 응답 처리 실패", Map.of(), 502), false, cause);
    }

    private static AiClientException unavailable() {
        return new AiClientException("AI_UNAVAILABLE", "AI 서버 응답 없음. 잠시 후 다시 시도", Map.of(), 502);
    }

    private static boolean isAny(Throwable c, List<Class<? extends Throwable>> types) {
        return types.stream().anyMatch(t -> t.isInstance(c));
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
                case "/v1/cases/ask" -> askVersion(body.path("generation_meta"));
                default -> null;
            };
            writer.write(AiCallLog.create(path, requestId, attempt, status, errorCode, durationMs,
                    version, text(body, "generation_id"), targetType, targetId));
        } catch (RuntimeException e) {
            log.warn("ai call log failed path={} request_id={} try={}", path, requestId, attempt);
        }
    }

    /**
     * 질문 답변 기록 버전 — `prompt_version`, OUT_OF_SCOPE(답 호출 없음)면 `plan_prompt_version` (SCRUM-291).
     * 성공·실패 행 모두 응답 확인 전 원문에서 읽으므로, 칸 모양(문자열 · `ai_version` 칸 길이 64 이하)이 아니면 null.
     */
    static String askVersion(JsonNode meta) {
        JsonNode answerVersion = meta.get("prompt_version");
        if (answerVersion != null && !answerVersion.isNull()) return versionOrNull(answerVersion);
        return versionOrNull(meta.get("plan_prompt_version"));
    }

    private static String versionOrNull(JsonNode v) {
        return v != null && v.isTextual() && v.textValue().length() <= AI_VERSION_MAX ? v.textValue() : null;
    }

    private record Response(int status, JsonNode body) {}

    private ClassifiedResponse withStatus(int status, Throwable error) {
        Classified c = classify(error);
        Integer aiStatus = error instanceof ReadTimeoutException || error instanceof TimeoutException ? null : status;
        return new ClassifiedResponse(c.ex(), c.retryable(), aiStatus, c.cause());
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

    /** 실패 분류 결과 — 예외와 재시도 여부, 로그 cause= 칸에 찍을 원인 클래스 이름(없으면 null). 테스트가 직접 검사한다. */
    record Classified(AiClientException ex, boolean retryable, String cause) {}

    /** 응답 오류 분류 결과를 재시도 루프로 전달하는 내부 캐리어(HTTP 상태까지 반영해 재시도 여부 결정). */
    private static final class ClassifiedResponse extends RuntimeException {
        final transient AiClientException ex;
        final boolean retryable;
        final Integer aiStatus;
        final String cause;
        ClassifiedResponse(AiClientException ex, boolean retryable, Integer aiStatus, String cause) {
            super(null, null, false, false);   // 스택트레이스 없이 가볍게
            this.ex = ex;
            this.retryable = retryable;
            this.aiStatus = aiStatus;
            this.cause = cause;
        }
    }
}
