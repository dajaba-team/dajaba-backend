package com.team.aml.shared.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 실제 AI 서버 호출 (aml.ai.mock=false, 규약 v0.2 §6). 유스케이스는 AiClient 인터페이스만 본다.
 *
 * 공통 동작:
 *  - 헤더 X-Internal-Key, X-Request-Id(MDC request_id, 없거나 공백이면 UUID, 재시도해도 같은 값), X-Budget-Ms(그 시도 때 남은 예산 ms)
 *  - 화면 요청 전체 90초(재시도 포함) = aml.ai.timeout-seconds. 점수는 500건 묶음마다 같은 한도.
 *    질문 답변(/v1/cases/ask)만 전체 30초 = aml.ai.ask-timeout-seconds (규약 6.2, SCRUM-291). 한도는 호출마다 따로 잰다.
 *    참고 사례 검색(/v1/references/search)만 전체 8초 = aml.ai.reference-search-timeout-seconds (규약 6.2, SCRUM-309).
 *    승인 STR 색인(/v1/references/str)만 전체 30초 = aml.ai.str-index-timeout-seconds (SCRUM-357).
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
    private final SelfBudgetedCall searchCall;
    private final SelfBudgetedCall indexCall;
    private static final String SEARCH_PATH = "/v1/references/search";
    private static final String STR_INDEX_PATH = "/v1/references/str";

    public WebClientAiClient(WebClient aiWebClient, AmlProperties props, ObjectMapper om, AiCallLogWriter writer) {
        this.http = aiWebClient;
        this.writer = writer;
        this.om = om;
        this.internalKey = props.ai().internalKey();
        this.maxBatch = props.ai().maxBatch();
        this.budget = Duration.ofSeconds(props.ai().timeoutSeconds());
        this.askBudget = Duration.ofSeconds(props.ai().askTimeoutSeconds());
        this.searchCall = new SelfBudgetedCall(SEARCH_PATH,
                Duration.ofSeconds(props.ai().referenceSearchTimeoutSeconds()), SEARCH_ERROR_MESSAGES, "참고 사례 검색");
        this.indexCall = new SelfBudgetedCall(STR_INDEX_PATH,
                Duration.ofSeconds(props.ai().strIndexTimeoutSeconds()), STR_INDEX_ERROR_MESSAGES, "승인 STR 색인");
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

    /**
     * 참고 사례 검색 — 이 호출만 searchCall 예산(8초). `draftId` 는 기록에만(본문 없음). SCRUM-309.
     * 호출·재시도 루프는 {@link #selfBudgetedPost} — 응답 확인만 {@link #requireReferenceSearchResponse}.
     */
    @Override
    public ReferenceSearchResponse searchReferences(String draftId, ReferenceSearchRequest request) {
        return selfBudgetedPost(searchCall, request, draftId, r -> requireReferenceSearchResponse(request, r),
                "status", ReferenceSearchResponse.class);
    }

    /**
     * 자기 호출·재시도 루프를 쓰는 호출 하나의 설정 — 경로 · 전체 예산 · 그 입구가 낼 수 있는 AI 오류 코드와 코드별 고정 문구 ·
     * 오류 문구에 쓰는 이름(예: "참고 사례 검색"). 참고 사례 검색(SCRUM-309)·승인 STR 색인(SCRUM-357) 둘.
     */
    private record SelfBudgetedCall(String path, Duration budget, Map<String, String> errorMessages, String label) {}

    /**
     * 자기 예산 호출 — 다른 다섯 호출과 달리 자기 호출·재시도 루프를 쓴다(309 추가 검토 1·2). 재시도 횟수·남은 시간·대상 판정은
     * {@link #withRetry} 와 같고 분류는 {@link #classify} 그대로. 다른 점:
     *  - 요청·응답을 바이트로 주고받고 `om` 으로 직접 읽는다 — Jackson 코덱 DEBUG/TRACE 로그에 요청·응답 본문이 안 남게
     *  - `X-Budget-Ms` 는 연결을 잡은 뒤(요청 헤더 커밋 직전) 절대 마감 시각에서 남은 ms 로 계산. 1ms 미만이면 보내지 않고
     *    재시도 없는 AI_TIMEOUT
     *  - AI 오류 본문은 그 입구가 낼 수 있는 코드만 그대로, 나머지는 AI_BAD_RESPONSE. 메시지는 코드별 고정 문구(AI 문장 안 씀)
     * 기록은 다섯 호출과 같이 호출 스레드에서 시도마다 저장한다 — 예산은 AI 호출(재시도 포함) 기준, 감사 DB 저장 시간은 빠짐:
     * 재시도 전 저장에 쓴 시간만큼 다음 시도 마감을 뒤로 민다(309 추가 검토 3). 그래서 전체 걸린 시간은 예산 + 저장 시간까지 늘 수 있다.
     * 기록 행은 `DRAFT` 대상, `generation_id` 는 늘 null(계약에 없는 칸), 버전은 `retrieval_version` 만 넘긴다.
     * 2xx 응답은 {@code check} 로 확인한 뒤 읽는다 — 틀리면 재시도 없는 실패 행.
     */
    private <T> T selfBudgetedPost(SelfBudgetedCall call, Object request, String draftId, Consumer<JsonNode> check,
                                   String requiredField, Class<T> type) {
        byte[] body;
        try {
            body = om.writeValueAsBytes(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI " + call.label() + " 요청 만들기 실패", Map.of(), 502);
        }
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + call.budget().toNanos();
        Classified last = null;
        for (int tries = 1; tries <= 2; tries++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.toMillis() <= 0) break;                 // 남은 시간 없으면 재시도 안 함
            long start = System.nanoTime();
            Response response;
            try {
                response = selfBudgetedExchange(call, body, requestId, deadline, remaining);
            } catch (RuntimeException e) {
                long ended = System.nanoTime();
                long leftNanos = deadline - ended;                // 시도가 끝난 순간 남은 AI 예산을 확정
                last = classify(e);
                Integer status = e instanceof ClassifiedResponse cr ? cr.aiStatus : null;
                recordSelfBudgetedAttempt(call, requestId, tries, status, last.ex().code(), start, null, draftId);
                log.warn("ai call failed path={} request_id={} code={} http={} retryable={} cause={} ms={} try={}",
                        call.path(), requestId, last.ex().code(), last.ex().httpStatus(), last.retryable(), last.cause(),
                        (ended - start) / 1_000_000, tries);
                if (!last.retryable()) break;
                deadline = System.nanoTime() + leftNanos;         // 감사 저장 시간은 빼고 다음 시도 마감을 다시 잡음
                continue;
            }
            T value;
            try {
                check.accept(response.body());
                value = readRecord(response.body(), requiredField, type, "AI " + call.label() + " 응답 파싱 실패");
            } catch (RuntimeException e) {
                Classified rejected = classify(e);
                AiClientException bad = rejected.ex();
                recordSelfBudgetedAttempt(call, requestId, tries, response.status(), bad.code(), start, response.body(),
                        draftId);
                log.warn("ai response rejected path={} request_id={} code={} http={} cause={} ms={} try={}",
                        call.path(), requestId, bad.code(), response.status(), rejected.cause(), msSince(start), tries);
                throw bad;
            }
            recordSelfBudgetedAttempt(call, requestId, tries, response.status(), null, start, response.body(), draftId);
            log.info("ai call ok path={} request_id={} ms={} try={}", call.path(), requestId, msSince(start), tries);
            return value;
        }
        throw last != null ? last.ex() : new AiClientException("AI_TIMEOUT", "AI 서버 응답 시간 초과", Map.of(), 502);
    }

    /**
     * 자기 예산 호출 한 시도 — 본문은 바이트(코덱 본문 로그 없음). `X-Budget-Ms` 는 `httpRequest` 콜백에서 정한다: WebClient 가
     * 연결을 잡은 뒤 요청을 쓰기 직전에 부르는 자리라, 풀 대기·연결 수립에 쓴 시간이 빠진 값이 나간다({@link #searchBudgetMs}).
     * 응답 처리는 {@link #exchange} 와 같고, 오류 본문만 {@link #selfBudgetedErrorBody} 로 걸러 넘긴다.
     */
    private Response selfBudgetedExchange(SelfBudgetedCall call, byte[] body, String requestId, long deadline,
                                          Duration remaining) {
        return http.post().uri(call.path()).contentType(MediaType.APPLICATION_JSON)
                .header(HEADER_INTERNAL_KEY, internalKey)
                .header(HEADER_REQUEST_ID, requestId)
                .httpRequest(req -> req.getHeaders().set(HEADER_BUDGET_MS,
                        String.valueOf(searchBudgetMs(deadline, System.nanoTime()))))
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    int status = resp.statusCode().value();
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(byte[].class)
                                .switchIfEmpty(Mono.error(badResponse("AI 응답 본문이 비어 있음")))
                                .map(bytes -> new Response(status, readSelfBudgetedJson(bytes)))
                                .onErrorMap(e -> withStatus(status, e));
                    }
                    return resp.bodyToMono(byte[].class)
                            .map(this::readSelfBudgetedErrorJson)
                            .defaultIfEmpty(om.createObjectNode())
                            .map(err -> selfBudgetedErrorBody(call, err))
                            .<Response>flatMap(err -> Mono.error(toClassified(status, err)))
                            .onErrorMap(e -> withStatus(status, e));
                }).timeout(remaining).block();
    }

    /**
     * 2xx 본문 — JSON 값 **하나**여야 하고 같은 칸이 두 번 오면 안 된다. 뒤에 다른 값이 붙거나(추가 검토 1) 같은 키가
     * 겹치면(Jackson 기본은 뒤 값으로 덮어씀, 추가 검토 2) AI_BAD_RESPONSE. 다른 다섯 호출은 이번에 그대로.
     */
    private JsonNode readSelfBudgetedJson(byte[] bytes) {
        try {
            JsonNode node = om.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(bytes);
            if (node == null || node.isMissingNode()) throw badResponse("AI 응답 본문이 비어 있음");
            return node;
        } catch (java.io.IOException e) {
            throw new AiClientException("AI_BAD_RESPONSE", "AI 응답 처리 실패", Map.of(), 502);
        }
    }

    /** 오류 본문 — JSON 이 아니면 빈 객체(코드 없음 → AI_BAD_RESPONSE, {@link #exchange} 의 디코딩 실패와 같은 결과). */
    private JsonNode readSelfBudgetedErrorJson(byte[] bytes) {
        try {
            JsonNode node = om.readTree(bytes);
            return node != null && node.isObject() ? node : om.createObjectNode();
        } catch (java.io.IOException e) {
            return om.createObjectNode();
        }
    }

    /**
     * 검색이 낼 수 있는 AI 오류 코드(AI `errors.py` — 검색 입구 4종 + 공통 인증·내부 오류 + 재시도 대상 공통 `MODEL_UNAVAILABLE`)와
     * 코드별 고정 문구. 여기 없는 코드는 AI_BAD_RESPONSE 로 바꾼다 — AI 가 준 `code`·`message` 문자열이 로그·`error_code`·
     * 화면 오류 응답으로 가지 않게(309 추가 검토 2).
     */
    private static final Map<String, String> SEARCH_ERROR_MESSAGES = Map.of(
            "INVALID_REQUEST", "AI 참고 사례 검색 요청이 거부됨",
            "UNAUTHENTICATED", "AI 서버 인증 실패",
            "LLM_UNAVAILABLE", "AI 참고 사례 검색 일시 실패. 잠시 후 다시 시도",
            "LLM_BUDGET_EXCEEDED", "AI 사용 한도 초과",
            "MODEL_UNAVAILABLE", "AI 모델 일시 사용 불가. 잠시 후 다시 시도",
            "DB_UNAVAILABLE", "AI 서버 DB 일시 실패. 잠시 후 다시 시도",
            "INTERNAL", "AI 서버 오류");
    private static final String SELF_BUDGETED_UNKNOWN_ERROR_MESSAGE = "AI 응답 처리 실패";

    /** 오류 본문 → `code`·`message` 두 칸만 다시 만든다. 아는 코드면 그대로 + 고정 문구, 모르면 AI_BAD_RESPONSE. */
    private JsonNode selfBudgetedErrorBody(SelfBudgetedCall call, JsonNode err) {
        JsonNode code = err.get("code");
        Map<String, String> messages = call.errorMessages();
        String known = code != null && code.isTextual() && messages.containsKey(code.textValue())
                ? code.textValue() : null;
        return om.createObjectNode()
                .put("code", known != null ? known : "AI_BAD_RESPONSE")
                .put("message", known != null ? messages.get(known) : SELF_BUDGETED_UNKNOWN_ERROR_MESSAGE);
    }

    /**
     * 보낼 `X-Budget-Ms` — 마감까지 남은 정수 ms. 1 미만이면 보내지 않는다(AI 는 0 을 400 으로 거부) — 응답 대기 타임아웃과
     * 같은 예외라 {@link #classify} 가 재시도 없는 AI_TIMEOUT 으로 분류한다(309 추가 검토 2). 자기 예산 호출 모두 이 값.
     */
    static long searchBudgetMs(long deadlineNanos, long nowNanos) {
        long ms = (deadlineNanos - nowNanos) / 1_000_000;
        if (ms < 1) throw ReadTimeoutException.INSTANCE;
        return ms;
    }

    /**
     * 자기 예산 호출 시도 한 행 — 다섯 호출과 같이 호출 스레드에서 {@link #recordAttempt} 로 바로 저장. 기록 본문은
     * `retrieval_version` 하나만 넘겨 `generation_id` 는 늘 null(응답에 계약 밖 칸이 와도), 걸린 시간은 HTTP 시도 시간.
     */
    private void recordSelfBudgetedAttempt(SelfBudgetedCall call, String requestId, int attempt, Integer status,
                                           String errorCode, long start, JsonNode body, String draftId) {
        JsonNode logBody = null;
        if (body != null) {
            ObjectNode only = om.createObjectNode();
            if (body.has("retrieval_version")) only.set("retrieval_version", body.get("retrieval_version"));
            logBody = only;
        }
        recordAttempt(call.path(), requestId, attempt, status, errorCode, start, logBody, "DRAFT", draftId);
    }
    /** AI `ReferenceResult.score`(cosine) 범위. */
    private static final double REFERENCE_SCORE_MIN = -1.0;
    private static final double REFERENCE_SCORE_MAX = 1.0;

    /**
     * 참고 사례 검색 응답 확인 (규약 5.5 = AI `reference_search.py` 규칙 + 보낸 값 대조, SCRUM-309).
     * 틀리면 AI_BAD_RESPONSE 502, 메시지엔 칸 이름만(검색어·문서 제목 없음). AI 스키마보다 더 보는 곳: 결과 수 ≤ 보낸
     * `top_k`(스키마는 10), 결과 유형 ∩ 보낸 유형 ≠ ∅, `source_type` ∈ 보낸 목록, `produced_at` 은 Z 만.
     * 안 보는 곳: 결과 유형 원소가 AI 유형 이름인지, 모르는 칸(원칙 4 — 받아 둠).
     */
    static void requireReferenceSearchResponse(ReferenceSearchRequest sent, JsonNode r) {
        for (String field : List.of("produced_at", "status", "results", "retrieval_version", "search_meta")) {
            if (!r.hasNonNull(field)) throw badReference(field);
        }
        ReferenceSearchStatus status = referenceStatus(r.get("status"));
        if (!isUtcZ(r.get("produced_at"))) throw badReference("produced_at");
        JsonNode results = r.get("results");
        if (!results.isArray()) throw badReference("results");
        JsonNode version = r.get("retrieval_version");
        if (!version.isTextual() || version.textValue().isEmpty()) throw badReference("retrieval_version");
        requireReferenceMeta(r.get("search_meta"));

        if ((status == ReferenceSearchStatus.FOUND) == results.isEmpty()) throw badReference("results");   // FOUND ↔ 1건 이상
        if (results.size() > sent.topK()) throw badReference("results");

        Set<String> sentTypologies = sent.typologies() == null ? Set.of() : Set.copyOf(sent.typologies());
        Set<String> sentSources = sent.sourceTypes() == null ? Set.of() : Set.copyOf(sent.sourceTypes());
        double previous = Double.POSITIVE_INFINITY;
        for (JsonNode item : results) {
            if (!item.isObject()) throw badReference("results");
            for (String field : List.of("doc_id", "doc_title", "chunk_id")) {
                if (!item.path(field).isTextual()) throw badReference(field);
            }
            JsonNode page = item.get("page");
            if (page == null || !page.isIntegralNumber() || !page.canConvertToInt()) throw badReference("page");
            JsonNode publisher = item.get("publisher");   // 칸은 있어야 함(값 null 허용)
            if (publisher == null || !(publisher.isNull() || publisher.isTextual())) throw badReference("publisher");
            JsonNode year = item.get("year");
            if (year == null || !(year.isNull() || (year.isIntegralNumber() && year.canConvertToInt()))) {
                throw badReference("year");
            }
            JsonNode score = item.get("score");
            if (score == null || !score.isNumber() || score.doubleValue() < REFERENCE_SCORE_MIN
                    || score.doubleValue() > REFERENCE_SCORE_MAX || score.doubleValue() > previous) {
                throw badReference("score");
            }
            previous = score.doubleValue();
            requireReferenceTypologies(item.get("typologies"), sentTypologies);
            JsonNode source = item.get("source_type");
            if (source == null || !source.isTextual() || !sentSources.contains(source.textValue())) {
                throw badReference("source_type");
            }
        }
    }

    /** 결과 유형 — 문자열 1개 이상, 보낸 유형과 하나 이상 겹침. 검색 5종 밖 태그가 섞여도 됨. */
    private static void requireReferenceTypologies(JsonNode tags, Set<String> sent) {
        if (tags == null || !tags.isArray() || tags.isEmpty()) throw badReference("typologies");
        boolean overlaps = false;
        for (JsonNode tag : tags) {
            if (!tag.isTextual()) throw badReference("typologies");
            overlaps |= sent.contains(tag.textValue());
        }
        if (!overlaps) throw badReference("typologies");
    }

    /** AI `ReferenceSearchMeta` — `degraded` 참거짓, `candidates`·`latency_ms` 0 이상 정수. */
    private static void requireReferenceMeta(JsonNode m) {
        if (!m.isObject()) throw badReference("search_meta");
        if (!m.path("degraded").isBoolean()) throw badReference("search_meta.degraded");
        for (String field : List.of("candidates", "latency_ms")) {
            JsonNode v = m.get(field);
            if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 0) {
                throw badReference("search_meta." + field);
            }
        }
    }

    private static ReferenceSearchStatus referenceStatus(JsonNode v) {
        if (v.isTextual()) {
            for (ReferenceSearchStatus s : ReferenceSearchStatus.values()) {
                if (s.name().equals(v.textValue())) return s;
            }
        }
        throw badReference("status");
    }

    /** UTC `Z` 로 끝나고 ISO 시각으로 읽히는가 (규약 1.4). */
    private static boolean isUtcZ(JsonNode v) {
        if (!v.isTextual() || !v.textValue().endsWith("Z")) return false;
        try {
            Instant.parse(v.textValue());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static AiClientException badReference(String field) {
        return new AiClientException("AI_BAD_RESPONSE", "AI 참고 사례 검색 응답 확인 실패: " + field, Map.of(), 502);
    }

    /**
     * 승인 STR 색인 — 이 호출만 indexCall 예산(30초). 초안 번호는 요청 `draft_id` 그대로 기록(`DRAFT`)에 쓴다. SCRUM-357.
     * 호출·재시도 루프는 검색과 같은 {@link #selfBudgetedPost} — 응답 확인만 {@link #requireStrIndexResponse}.
     * 승인 뒤 뒤 스레드에서 불리지만 예산·재시도·기록·로그 규칙은 검색과 같다(섹션 글은 로그·기록에 안 남음).
     */
    @Override
    public StrIndexResponse indexApprovedStr(StrIndexRequest request) {
        return selfBudgetedPost(indexCall, request, request.draftId(), r -> requireStrIndexResponse(request, r),
                "status", StrIndexResponse.class);
    }

    /**
     * 색인이 낼 수 있는 AI 오류 코드와 고정 문구 — 검색과 같은 7개(★A 11/22). 계약 표는 400·502 `LLM_UNAVAILABLE`·503
     * `DB_UNAVAILABLE` 이지만 임베딩이 공통 클라이언트라 일 한도(`LLM_BUDGET_EXCEEDED`)·`MODEL_UNAVAILABLE` 도 날 수 있다.
     */
    private static final Map<String, String> STR_INDEX_ERROR_MESSAGES = Map.of(
            "INVALID_REQUEST", "AI 승인 STR 색인 요청이 거부됨",
            "UNAUTHENTICATED", "AI 서버 인증 실패",
            "LLM_UNAVAILABLE", "AI 승인 STR 색인 일시 실패. 잠시 후 다시 시도",
            "LLM_BUDGET_EXCEEDED", "AI 사용 한도 초과",
            "MODEL_UNAVAILABLE", "AI 모델 일시 사용 불가. 잠시 후 다시 시도",
            "DB_UNAVAILABLE", "AI 서버 DB 일시 실패. 잠시 후 다시 시도",
            "INTERNAL", "AI 서버 오류");

    private static final List<String> STR_INDEX_FIELDS =
            List.of("produced_at", "report_no", "status", "skip_reason", "chunk_count", "retrieval_version");

    /**
     * 승인 STR 색인 응답 확인 (AI `str_index.py` 규칙 + 보낸 값 대조, SCRUM-357). 틀리면 AI_BAD_RESPONSE 502, 메시지엔 칸 이름만.
     * 여섯 칸은 키가 있어야 한다(값 null 은 아래대로). INDEXED ↔ 이유 null·청크 1 이상·버전 앞뒤 공백 뺀 1자 이상 /
     * SKIPPED ↔ 이유 4종 중 하나·청크 0·버전 문자열 또는 null(DISABLED 면 AI 가 null).
     * AI 스키마보다 더 보는 곳: `report_no` = 보낸 값, 청크 수 ≤ {@link #maxStrIndexChunks 상한}(SCRUM-361).
     * 안 보는 곳: SKIPPED 의 버전 null 을 DISABLED 에만 묶기(AI 도 안 묶음), 모르는 칸(원칙 4 — 받아 둠).
     */
    static void requireStrIndexResponse(StrIndexRequest sent, JsonNode r) {
        if (r == null || !r.isObject()) throw badStrIndex("body");
        for (String field : STR_INDEX_FIELDS) {
            if (!r.has(field)) throw badStrIndex(field);
        }
        if (!isUtcZ(r.get("produced_at"))) throw badStrIndex("produced_at");
        JsonNode reportNo = r.get("report_no");
        if (!reportNo.isTextual() || !reportNo.textValue().equals(sent.reportNo())) throw badStrIndex("report_no");
        StrIndexStatus status = strIndexEnum(StrIndexStatus.class, r.get("status"), "status");
        JsonNode chunks = r.get("chunk_count");
        if (!chunks.isIntegralNumber() || !chunks.canConvertToInt()) throw badStrIndex("chunk_count");
        int chunkCount = chunks.intValue();
        JsonNode version = r.get("retrieval_version");
        if (!version.isNull() && !version.isTextual()) throw badStrIndex("retrieval_version");
        JsonNode reason = r.get("skip_reason");
        if (status == StrIndexStatus.INDEXED) {
            if (!reason.isNull()) throw badStrIndex("skip_reason");
            if (chunkCount < 1 || chunkCount > maxStrIndexChunks(sent)) throw badStrIndex("chunk_count");
            // AI 는 앞뒤 공백을 지운 뒤 비었는지 본다(str_strip_whitespace) — 공백뿐이면 실패(추가 검토 1)
            if (!version.isTextual() || stripLikePython(version.textValue()).isEmpty()) {
                throw badStrIndex("retrieval_version");
            }
        } else {
            if (reason.isNull()) throw badStrIndex("skip_reason");
            strIndexEnum(StrIndexSkipReason.class, reason, "skip_reason");
            if (chunkCount != 0) throw badStrIndex("chunk_count");
        }
    }

    /** AI 청크 상한 1200자의 절반 — 이웃 두 청크 합이 늘 1200 을 넘어서 청크 하나가 평균 600자 넘게 차지한다. */
    private static final int STR_INDEX_HALF_CHUNK = 600;

    /**
     * INDEXED 청크 수 상한 (SCRUM-361) = 공백만이 아닌 섹션마다 ⌊글자 수 ÷ 600⌋ + 1 의 합.
     * AI 는 단락을 문장 끝에서 앞부터 1200자까지 채우고 넘치면 다음 청크로 넘긴다(1200 넘는 한 문장은 1200자씩 자름)
     * → 이웃 두 청크 합 > 1200 → 청크 k 개면 글자 수 > 600×(k−1). 옛 AI(섹션 하나 = 청크 하나)도 이 안.
     * 글자 수는 원문 그대로 코드 포인트(AI `len` 과 같음, 앞뒤 공백 안 뺌). 공백만(파이썬 `strip()` 기준)·null 섹션은 0.
     */
    private static long maxStrIndexChunks(StrIndexRequest sent) {
        if (sent.sections() == null) return 0;
        long bound = 0;
        for (StrIndexSection s : sent.sections()) {
            if (s == null || s.text() == null || isBlankLikePython(s.text())) continue;
            bound += s.text().codePointCount(0, s.text().length()) / STR_INDEX_HALF_CHUNK + 1;
        }
        return bound;
    }

    private static <E extends Enum<E>> E strIndexEnum(Class<E> type, JsonNode v, String field) {
        if (v.isTextual()) {
            for (E e : type.getEnumConstants()) {
                if (e.name().equals(v.textValue())) return e;
            }
        }
        throw badStrIndex(field);
    }

    private static AiClientException badStrIndex(String field) {
        return new AiClientException("AI_BAD_RESPONSE", "AI 승인 STR 색인 응답 확인 실패: " + field, Map.of(), 502);
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
                case "/v1/references/search", "/v1/references/str" -> retrievalVersionOrNull(body.get("retrieval_version"));
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

    /** manifest 해시 — `retrieval_version` 마지막 `@` 뒤 소문자 16진 8자(AI `base_retrieval_version`). */
    private static final Pattern MANIFEST_HASH = Pattern.compile("[0-9a-f]{8}");

    /**
     * 자기 예산 호출(검색·색인) 기록 버전 — `retrieval_version` 마지막 `@` 뒤가 정확히 소문자 16진 8자면 **`@` + 그 8자만**
     * (예 `@36b5a2d5`), 아니면 null. 모델 이름(앞부분)은 기록에 안 남긴다 — 모양으로는 "모델 이름"과 AI 가 잘못 담은 섹션 글을
     * 가를 수 없어서(추가 검토 2). 응답 확인 전 원문에서 읽으므로 2xx 확인 실패 행도 같은 규칙. 응답 DTO 의 값은 받은 그대로.
     */
    static String retrievalVersionOrNull(JsonNode v) {
        if (v == null || !v.isTextual()) return null;
        String text = v.textValue();
        int at = text.lastIndexOf('@');
        if (at < 0) return null;
        String hash = text.substring(at + 1);
        return MANIFEST_HASH.matcher(hash).matches() ? "@" + hash : null;
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
