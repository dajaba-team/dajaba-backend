package com.team.aml.shared.ai;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.team.aml.shared.ai.DlRankingExamples.ML_MODEL;
import static com.team.aml.shared.ai.DlRankingExamples.at;
import static com.team.aml.shared.ai.DlRankingExamples.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-376 — `WebClientAiClient.getDlRankings` (`GET /v1/dl-rankings/{scan_date}`). 호출·재시도 루프는 검색·색인과 같은
 * {@code callSelfBudgeted} — 여기서는 GET·본문 없음·경로·예산(10초 기본)·오류 표·재시도 0(연결 거부만 1회)·기록 칸·
 * 로그에 경보 번호가 없는지를 본다. 응답 칸 규칙은 {@link DlRankingResponseCheckTest}.
 */
class WebClientDlRankingTest {

    private static final String KEY = "test-internal-key-0123456789abcd";
    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final String SECRET = "비밀스냅샷경로 /srv/snap";

    private final ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());   // 앱 ObjectMapper 와 같이 LocalDate 를 읽게
    private MockWebServer server;
    private final Rows writer = new Rows();
    private WebClientAiClient client;

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
        client = newClient(server.url("/").toString(), 5, 5, 5, 5, 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** 기본·질문 답변·참고 사례 검색·승인 STR 색인·ML+DL 조사 순위 한도(초). */
    private WebClientAiClient newClient(String baseUrl, int timeoutSeconds, int askSeconds, int searchSeconds,
                                        int indexSeconds, int rankingSeconds) {
        var props = new AmlProperties(new AmlProperties.Ai(baseUrl, KEY, timeoutSeconds, 500, false,
                askSeconds, false, 5, searchSeconds, false, indexSeconds, false, rankingSeconds), null, null, null);
        return new WebClientAiClient(WebClient.builder().baseUrl(baseUrl).build(), props, om, writer);
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String ok() {
        return valid(DATE).toString();
    }

    private static String example(String name) throws Exception {
        return Files.readString(Path.of("contracts/examples/" + name));
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
    void 정상_GET_본문없이_날짜_경로로_보내고_세_이유를_읽으며_날짜로_한_행을_남긴다() throws Exception {
        server.enqueue(json(200, ok()));

        DlRankingResponse r = client.getDlRankings(DATE);

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getMethod()).isEqualTo("GET");
        assertThat(sent.getPath()).isEqualTo("/v1/dl-rankings/2022-09-07");
        assertThat(sent.getBodySize()).isZero();
        assertThat(sent.getHeader("X-Internal-Key")).isEqualTo(KEY);
        assertThat(sent.getHeader("X-Request-Id")).isNotBlank();
        assertThat(budgetMs(sent)).isPositive().isLessThanOrEqualTo(5_000);

        assertThat(r.scanDate()).isEqualTo(DATE);
        assertThat(r.snapshotSha256()).isEqualTo(DlRankingExamples.SHA);
        assertThat(r.mlModel()).isEqualTo(ML_MODEL);
        assertThat(r.dlModel()).isEqualTo(DlRankingExamples.DL_MODEL);
        assertThat(r.evidenceTransactions()).isEqualTo(10);
        assertThat(r.scoredTransactions()).isEqualTo(9);
        assertThat(r.linkedAlerts()).isEqualTo(3);
        assertThat(r.mlOnlyAlerts()).isEqualTo(1);
        assertThat(r.unscoredAlerts()).isEqualTo(2);
        assertThat(r.alerts()).extracting(DlRankedAlert::fallbackReason).containsExactly(null,
                DlFallbackReason.INCOMPLETE_DL_EVIDENCE, null, null, DlFallbackReason.ML_UNSCORED,
                DlFallbackReason.ML_UNSCORED);
        DlRankedAlert unscoredWithDl = r.alerts().get(4);
        assertThat(unscoredWithDl.alertId()).isEqualTo("ALT-20220907-000004");
        assertThat(unscoredWithDl.mlScore()).isNull();
        assertThat(unscoredWithDl.mlFailureCode()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(unscoredWithDl.dlScore()).isEqualTo(0.5);
        assertThat(unscoredWithDl.mlRank()).isNull();
        assertThat(unscoredWithDl.fusedRank()).isNull();
        DlRankedAlert moved = r.alerts().get(2);
        assertThat(moved.ruleId()).isEqualTo("R03");
        assertThat(moved.mlRank()).isEqualTo(4);
        assertThat(moved.fusedRank()).isEqualTo(3);
        assertThat(moved.evidenceCount()).isEqualTo(1);
        assertThat(moved.dlLinked()).isEqualTo(1);
        assertThat(moved.referenceTs()).isEqualTo("2022-09-07T10:24:06");   // SCRUM-415 — 문자열 그대로
        assertThat(moved.evidenceDigest()).isEqualTo(DlRankingExamples.evidenceDigest(6));

        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getEndpoint()).isEqualTo("/v1/dl-rankings");   // 날짜 뺀 고정 경로
        assertThat(row.getTargetType()).isNull();
        assertThat(row.getTargetId()).isEqualTo("2022-09-07");
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getAttempt()).isEqualTo((short) 1);
        assertThat(row.getAiVersion()).isNull();   // 순위는 버전을 안 남김(검토 1)
        assertThat(row.getGenerationId()).isNull();
        assertThat(row.getRequestId()).isEqualTo(sent.getHeader("X-Request-Id"));
    }

    @Test
    void 정상_한_자리_월일도_ISO_날짜로_경로에_넣는다() throws Exception {
        LocalDate d = LocalDate.of(2022, 1, 5);
        server.enqueue(json(200, valid(d).toString()));

        client.getDlRankings(d);

        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/dl-rankings/2022-01-05");
        assertThat(writer.rows.get(0).getTargetId()).isEqualTo("2022-01-05");
    }

    @Test
    void 검토1_H2_ml_model_에_경보_번호_점수가_섞여도_ai_version_은_성공_확인실패_모두_null() throws Exception {
        String leaky = "ALT-20220907-000001 0.9";
        ObjectNode ok = valid(DATE);
        ok.put("ml_model", leaky);
        ObjectNode bad = valid(DATE);
        bad.put("ml_model", leaky);
        bad.put("scan_date", "2022-09-08");
        server.enqueue(json(200, ok.toString()));
        server.enqueue(json(200, bad.toString()));

        assertThat(client.getDlRankings(DATE).mlModel()).isEqualTo(leaky);   // DTO 는 받은 그대로
        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));

        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsExactly(null, "AI_BAD_RESPONSE");
        assertThat(writer.rows).extracting(AiCallLog::getAiVersion).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getGenerationId).containsOnlyNulls();
    }

    // --- 예산 ---------------------------------------------------------------

    @Test
    void 정상_X_Budget_Ms_는_순위_예산이고_검색_질문_색인_초안은_각자_예산_그대로다() throws Exception {
        client = newClient(server.url("/").toString(), 6, 3, 2, 5, 4);
        server.enqueue(json(200, ok()));
        server.enqueue(json(200, example("reference_search_response_found.json")));
        server.enqueue(json(200, example("ask_response_answered.json")));
        ObjectNode indexed = (ObjectNode) om.readTree(example("str_index_response_indexed.json"));
        indexed.put("report_no", "STR-2026-000012");
        server.enqueue(json(200, indexed.toString()));
        server.enqueue(json(200, "{\"draft_id\":\"D1\"}"));

        client.getDlRankings(DATE);
        client.searchReferences("DRF-000312", new ReferenceSearchRequest(List.of("CYCLE"), null, 5, List.of("PUBLIC")));
        client.ask(AskRequest.of(om.treeToValue(om.readTree(example("summary_request.json")), CaseSummaryRequest.class),
                "질문"));
        client.indexApprovedStr(om.treeToValue(om.readTree(example("str_index_request.json")), StrIndexRequest.class));
        client.createDraft(new StrDraftRequest("D1", "CASE-1", null, List.of(), null, 0, null, null));

        assertThat(budgetMs(server.takeRequest())).isGreaterThan(3_000).isLessThanOrEqualTo(4_000);   // 순위 4초
        assertThat(budgetMs(server.takeRequest())).isPositive().isLessThanOrEqualTo(2_000);           // 검색 2초
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(2_000).isLessThanOrEqualTo(3_000);   // 질문 3초
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(4_000).isLessThanOrEqualTo(5_000);   // 색인 5초
        assertThat(budgetMs(server.takeRequest())).isGreaterThan(5_000).isLessThanOrEqualTo(6_000);   // 초안 6초
    }

    @Test
    void 경계_순위_예산으로_끊고_재시도하지_않는다() throws Exception {
        client = newClient(server.url("/").toString(), 5, 5, 5, 5, 1);
        server.enqueue(json(200, ok()).setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.getDlRankings(DATE))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_TIMEOUT");
                    assertThat(e.httpStatus()).isEqualTo(502);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isNull();
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(writer.rows.get(0).getTargetId()).isEqualTo("2022-09-07");
    }

    // --- 오류 · 재시도 ----------------------------------------------------------

    @Test
    void 실패_503_DL_RANKING_UNAVAILABLE_은_코드_그대로_502_재시도_없고_기록엔_원래_503() throws Exception {
        server.enqueue(json(503, "{\"code\":\"DL_RANKING_UNAVAILABLE\",\"message\":\"" + SECRET + "\"}"));
        server.enqueue(json(200, ok()));   // 재시도하면 이걸 받게 되므로 안 받아야 함

        Throwable e = catchThrowable(() -> client.getDlRankings(DATE));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo("DL_RANKING_UNAVAILABLE");
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).isEqualTo("ML+DL 조사 순위를 지금 쓸 수 없음");   // AI 문장 대신 고정 문구
        });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getStatusCode()).isEqualTo(503);
        assertThat(row.getErrorCode()).isEqualTo("DL_RANKING_UNAVAILABLE");
        assertThat(row.getAiVersion()).isNull();
        assertThat(row.getEndpoint()).isEqualTo("/v1/dl-rankings");
        assertThat(row.getTargetType()).isNull();
        assertThat(row.getTargetId()).isEqualTo("2022-09-07");
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({"401, UNAUTHENTICATED, AI_UNAUTHENTICATED", "400, INVALID_REQUEST, AI_INVALID_REQUEST"})
    void 실패_401_400_은_공통_바꿈대로_502_재시도_없다(int status, String code, String want) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"" + SECRET + "\"}"));
        server.enqueue(json(200, ok()));

        Throwable e = catchThrowable(() -> client.getDlRankings(DATE));

        assertThat(e).isInstanceOfSatisfying(AiClientException.class, ae -> {
            assertThat(ae.code()).isEqualTo(want);
            assertThat(ae.httpStatus()).isEqualTo(502);
            assertThat(ae.getMessage()).doesNotContain("비밀스냅샷경로");
        });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(status);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"500, INTERNAL", "503, DB_UNAVAILABLE", "502, LLM_UNAVAILABLE", "503, MODEL_UNAVAILABLE",
            "409, GENERATION_IN_PROGRESS", "502, 비밀스냅샷경로", "503, ''",
            "500, UNAUTHENTICATED", "503, UNAUTHENTICATED", "503, INVALID_REQUEST", "500, DL_RANKING_UNAVAILABLE",
            "401, DL_RANKING_UNAVAILABLE", "404, INVALID_REQUEST"})   // 검토 1 M5 — (HTTP, 코드) 쌍이 아니면 모르는 오류
    void 실패_순위가_낼_수_없는_AI_코드는_재시도_없이_AI_BAD_RESPONSE_고정_문구(int status, String code) throws Exception {
        server.enqueue(json(status, "{\"code\":\"" + code + "\",\"message\":\"" + SECRET + "\"}"));
        server.enqueue(json(200, ok()));

        Throwable e = catchThrowable(() -> client.getDlRankings(DATE));

        assertBadResponse(e);
        assertThat(e.getMessage()).doesNotContain("비밀스냅샷경로");
        assertThat(server.getRequestCount()).isEqualTo(1);   // 다른 호출에선 재시도 대상인 코드도 여기선 0회
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(status);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
    }

    @Test
    void 검토2_M1_503_오류_본문에_code_가_두_번이면_AI_BAD_RESPONSE_한_번_기록은_503() throws Exception {
        server.enqueue(json(503, "{\"code\":\"INTERNAL\",\"code\":\"DL_RANKING_UNAVAILABLE\"}"));
        server.enqueue(json(200, ok()));

        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(503);
    }

    @Test
    void 검토2_M1_503_오류_본문이_객체_두_개면_허용_코드가_앞이어도_AI_BAD_RESPONSE_한_번_기록은_503() throws Exception {
        server.enqueue(json(503, "{\"code\":\"DL_RANKING_UNAVAILABLE\"}{\"code\":\"INTERNAL\"}"));
        server.enqueue(json(200, ok()));

        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(writer.rows.get(0).getStatusCode()).isEqualTo(503);
    }

    @Test
    void 실패_오류_본문이_JSON_이_아니면_AI_BAD_RESPONSE() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void 경계_연결_거부만_1회_재시도하고_AI_UNAVAILABLE_두_행이다() throws Exception {
        int port = server.getPort();
        server.shutdown();
        WebClientAiClient c = newClient("http://localhost:" + port, 5, 5, 5, 5, 2);
        server = new MockWebServer();   // tearDown 용

        assertThatThrownBy(() -> c.getDlRankings(DATE))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(writer.rows).extracting(AiCallLog::getAttempt).containsExactly((short) 1, (short) 2);
        assertThat(writer.rows).extracting(AiCallLog::getStatusCode).containsOnlyNulls();
        assertThat(writer.rows).extracting(AiCallLog::getEndpoint).containsOnly("/v1/dl-rankings");
        assertThat(writer.rows).extracting(AiCallLog::getTargetId).containsOnly("2022-09-07");
    }

    // --- 2xx 응답 확인 실패 -------------------------------------------------------

    @Test
    void 실패_2xx_응답_확인이_틀리면_실패_행이고_재시도_없다() throws Exception {
        ObjectNode b = valid(DATE);
        b.put("scan_date", "2022-09-08");
        server.enqueue(json(200, b.toString()));
        server.enqueue(json(200, ok()));

        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(writer.rows).hasSize(1);
        AiCallLog row = writer.rows.get(0);
        assertThat(row.getStatusCode()).isEqualTo(200);
        assertThat(row.getErrorCode()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(row.getAiVersion()).isNull();
        assertThat(row.getTargetType()).isNull();
        assertThat(row.getEndpoint()).isEqualTo("/v1/dl-rankings");
    }

    @Test
    void 실패_2xx_본문_뒤에_JSON이_더_붙거나_같은_칸이_겹치면_AI_BAD_RESPONSE() throws Exception {
        server.enqueue(json(200, ok() + ok()));
        server.enqueue(json(200, ok().replaceFirst("\\{", "{\"scan_date\":\"2022-09-07\",")));

        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(writer.rows).extracting(AiCallLog::getErrorCode).containsOnly("AI_BAD_RESPONSE");
    }

    @Test
    void 실패_2xx_본문이_비었으면_AI_BAD_RESPONSE() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertBadResponse(catchThrowable(() -> client.getDlRankings(DATE)));
    }

    @Test
    void 실패_null_날짜는_보내지_않는다() {
        assertThatThrownBy(() -> client.getDlRankings(null)).isInstanceOf(NullPointerException.class);
        assertThat(server.getRequestCount()).isZero();
        assertThat(writer.rows).isEmpty();
    }

    // --- 로그 -----------------------------------------------------------------

    private static final List<String> BODY_LOGGERS = List.of("org.springframework.http.codec",
            "org.springframework.core.codec", "org.springframework.web.HttpLogging",
            "org.springframework.web.reactive.function.client", "reactor.netty");

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
    void 실패_TRACE_로그를_켜도_성공_실패_경로_모두_경보_번호와_점수가_안_남는다(CapturedOutput out) throws Exception {
        ObjectNode bad = valid(DATE);
        at(bad, 0).put("ml_rank", 9);
        server.enqueue(json(200, ok()));
        server.enqueue(json(200, bad.toString()));
        server.enqueue(json(503, "{\"code\":\"DL_RANKING_UNAVAILABLE\",\"message\":\"" + SECRET + "\"}"));

        withTraceLogging(() -> {
            client.getDlRankings(DATE);
            catchThrowable(() -> client.getDlRankings(DATE));
            catchThrowable(() -> client.getDlRankings(DATE));
        });

        assertThat(out.getAll()).contains("/v1/dl-rankings")
                .doesNotContain("ALT-20220907").doesNotContain("0.99").doesNotContain("TRANSACTION_NOT_FOUND")
                .doesNotContain("비밀스냅샷경로");
    }
}
