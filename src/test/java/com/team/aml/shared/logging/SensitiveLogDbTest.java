package com.team.aml.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.StrSection;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 민감 값이 로그에 안 나오는지 (SCRUM-272, NFR-COM-04, 01_code_rules §6 · 03_shared §8 로그 형식).
 * 실제 PostgreSQL + 필터 체인 전체 + 진짜 WebClientAiClient(가짜 AI = MockWebServer). 가짜 AI 응답·실패 본문에
 * 계좌·금액·본문 문장을 일부러 넣고, 그 요청을 도는 동안 찍힌 로그(OutputCapture 전체)에 그 값이 하나도 없어야 한다.
 * 로그 수준은 local 과 같게(com.team.aml DEBUG). 새는 곳이 있으면 이 테스트는 고치지 않고 ★C 목록으로 올린다.
 * CapturedOutput 은 클래스 시작부터 쌓이므로 테스트마다 시작 위치(mark) 뒤만 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.ai.mock=false", "aml.ai.internal-key=test-internal-key-0123456789abcd",
        "aml.ai.timeout-seconds=5", "logging.level.com.team.aml=DEBUG"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)   // 실패 때 MockMvc 가 찍는 요청·응답 본문은 앱 로그가 아니다
@ActiveProfiles("test")
@Import(SensitiveLogDbTest.DbProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class SensitiveLogDbTest {

    // --- 픽스처의 민감 값 (로그에 하나라도 나오면 실패) ---------------------------
    static final String ACCOUNT_NO = "8000EBD30";
    static final String ACCOUNT_KEY = "10_" + ACCOUNT_NO;
    static final String AMOUNT_PLAIN = "7654321.09";
    static final String AMOUNT_COMMA = "7,654,321.09";
    static final String NARRATIVE = "SENSITIVE-NARR-zq7 보고 대상 계좌(10_****BD30)로 7,654,321.09 US Dollar 입금";
    static final String EDITED = "SENSITIVE-EDIT-zq7 고친 문장 계좌 010_****BD30 금액 7654321.09";
    static final String MEMO = "SENSITIVE-MEMO-zq7 고객이 창구에서 현금 인출을 서둘렀다";
    static final List<String> FORBIDDEN = List.of(
            ACCOUNT_NO, ACCOUNT_KEY, "010_" + ACCOUNT_NO, "10_****", "010_****", "****BD30",
            AMOUNT_PLAIN, AMOUNT_COMMA, "7654321",
            "SENSITIVE-NARR-zq7", "SENSITIVE-EDIT-zq7", "SENSITIVE-MEMO-zq7", "현금 인출을 서둘렀다");

    private static final AtomicLong SEQ = new AtomicLong();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection(type = JdbcConnectionDetails.class)
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    /** 가짜 AI. 단계마다 응답 만드는 함수를 바꿔 끼운다. */
    static final MockWebServer AI = new MockWebServer();
    static final AtomicReference<Function<RecordedRequest, MockResponse>> REPLY = new AtomicReference<>();

    static {
        AI.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return REPLY.get().apply(request);
            }
        });
        try {
            AI.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
        registry.add("aml.ai.base-url", () -> AI.url("/").toString());
    }

    @AfterAll
    static void stopAi() throws IOException {
        AI.shutdown();
    }

    /** 500 처리기까지 가는 DB 제약 위반 — 키에 계좌가 든 unique(alerts_rule_account_window_uq). src/test 에만 있다. */
    @TestConfiguration
    @RestController
    static class DbProbe {
        @Autowired JdbcTemplate jdbc;

        @GetMapping("/api/v1/_probe/duplicate-alert")
        Map<String, String> duplicateAlert() {
            long rule = jdbc.queryForObject("select id from ops.rules where rule_id like 'R_SENS_%' order by id limit 1",
                    Long.class);
            insertAlert(jdbc, rule, "ALT-SENS-DUP-1", "E_SENS_DUP", "2022-09-03 12:00:00");
            insertAlert(jdbc, rule, "ALT-SENS-DUP-2", "E_SENS_DUP", "2022-09-03 12:00:00");   // 같은 규칙·계좌·창
            return Map.of();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired PlatformTransactionManager transactionManager;

    private long inv;
    private int mark;

    @BeforeEach
    void setUp(CapturedOutput output) {
        mark = output.getAll().length();
        inv = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values ('sens_inv01', 'sens_inv01', 'INVESTIGATOR', 1, 1)
                on conflict (login_id) do update set display_name = excluded.display_name
                returning id""", Long.class);
        REPLY.set(SensitiveLogDbTest::draftOk);
    }

    // --- 정상 경로 -----------------------------------------------------------

    @Test
    void 정상_상세_메모_초안_조회_수정_검증을_돌아도_민감_값이_로그에_없다(CapturedOutput output) throws Exception {
        String caseNo = escalatedCase();

        ok(mvc.perform(as(get("/api/v1/cases/" + caseNo))).andReturn(), 200);
        ok(mvc.perform(as(put("/api/v1/cases/" + caseNo + "/notes"))
                .content(JSON.writeValueAsString(Map.of("investigator_notes", MEMO, "version", version(caseNo)))))
                .andReturn(), 200);

        MvcResult created = mvc.perform(as(post("/api/v1/cases/" + caseNo + "/str-drafts"))
                .header("X-Request-Id", "rid-sens-draft-1")
                .content(JSON.writeValueAsString(Map.of("case_version", version(caseNo))))).andReturn();
        String draftNo = ok(created, 201).path("draft_no").asText();
        assertThat(created.getResponse().getHeader("X-Request-Id")).isEqualTo("rid-sens-draft-1");
        assertThat(lastAiRequest().getHeader("X-Request-Id")).isEqualTo("rid-sens-draft-1");   // AI 호출 = 요청 MDC 값

        ok(mvc.perform(as(get("/api/v1/str-drafts/" + draftNo))).andReturn(), 200);
        ok(mvc.perform(as(put("/api/v1/str-drafts/" + draftNo + "/revisions"))
                .content(JSON.writeValueAsString(Map.of("case_version", version(caseNo),
                        "sections", List.of(Map.of("section", StrSection.V2.get(0).name(), "text", EDITED))))))
                .andReturn(), 200);

        REPLY.set(SensitiveLogDbTest::verifyOk);
        ok(mvc.perform(as(post("/api/v1/str-drafts/" + draftNo + "/verify")).content("{}")).andReturn(), 200);

        assertNoSensitive(output);
    }

    // --- 실패 경로 -----------------------------------------------------------

    @Test
    void 실패_AI_5xx_본문에_민감_값이_있어도_로그에_없다(CapturedOutput output) throws Exception {
        String caseNo = escalatedCase();
        REPLY.set(r -> json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"" + NARRATIVE + " " + ACCOUNT_KEY
                + "\",\"request_id\":\"x\"}"));

        assertThat(createDraft(caseNo).getResponse().getStatus()).isEqualTo(502);

        assertNoSensitive(output);
    }

    @Test
    void 실패_AI_200인데_JSON이_깨져도_로그에_없다(CapturedOutput output) throws Exception {
        String caseNo = escalatedCase();
        REPLY.set(r -> json(200, "{\"draft_id\":\"" + NARRATIVE + " " + ACCOUNT_KEY + " " + AMOUNT_COMMA + "\", broken"));

        assertThat(createDraft(caseNo).getResponse().getStatus()).isEqualTo(502);

        assertNoSensitive(output);
    }

    @Test
    void 실패_AI_200인데_칸_값이_틀려도_로그에_없다(CapturedOutput output) throws Exception {
        String caseNo = escalatedCase();
        REPLY.set(r -> {
            ObjectNode body = draftBody(r);
            body.put("draft_id", "DRF-" + ACCOUNT_KEY);                         // 보낸 draft_id 와 다름
            ((ObjectNode) body.get("narrative").get(0)).put("section", NARRATIVE);   // 모르는 단락 이름
            return json(200, body.toString());
        });

        assertThat(createDraft(caseNo).getResponse().getStatus()).isEqualTo(502);

        assertNoSensitive(output);
    }

    @Test
    void 실패_검증_AI_5xx도_로그에_없다(CapturedOutput output) throws Exception {
        String caseNo = escalatedCase();
        String draftNo = ok(createDraft(caseNo), 201).path("draft_no").asText();
        REPLY.set(r -> json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"" + NARRATIVE + "\",\"request_id\":\"x\"}"));

        MvcResult r = mvc.perform(as(post("/api/v1/str-drafts/" + draftNo + "/verify")).content("{}")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(502);
        assertNoSensitive(output);
    }

    /**
     * pgjdbc 예외 메시지에는 `Detail: Key (rule_ref_id, account_key, window_start)=(…, 10_…, …)` 가 들어 있다.
     * 500 처리기는 DB 오류를 클래스·SQLState·제약 이름 한 줄로만 남긴다 (03_shared §8, SCRUM-272).
     */
    @Test
    void 실패_DB_제약_위반_500은_제약_이름만_남고_계좌는_로그에_없다(CapturedOutput output) throws Exception {
        escalatedCase();   // R_SENS_ 규칙과 계좌 키가 든 경보를 만든다

        MvcResult r = mvc.perform(as(get("/api/v1/_probe/duplicate-alert")).header("X-Request-Id", "rid-sens-db-1"))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(ok(r, 500).path("error").path("code").asText()).isEqualTo("INTERNAL_ERROR");
        List<String> lines = output.getAll().substring(mark).lines()
                .filter(l -> l.contains("unhandled exception")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("ERROR").contains("[rid-sens-db-1]")
                .endsWith("unhandled exception error_type=DuplicateKeyException sql_state=23505 "
                        + "constraint=alerts_rule_account_window_uq");
        assertThat(output.getAll().substring(mark)).doesNotContain("Detail:").doesNotContain("PSQLException");
        assertNoSensitive(output);
    }

    // --- 가짜 AI 응답 --------------------------------------------------------

    /** 규약 §5.2 모양 7항목. draft_id·evidence 는 받은 요청에서 그대로. 본문·facts 에 민감 값을 넣는다. */
    private static MockResponse draftOk(RecordedRequest r) {
        return json(200, draftBody(r).toString());
    }

    private static ObjectNode draftBody(RecordedRequest r) {
        JsonNode req = read(r);
        ObjectNode body = JSON.createObjectNode()
                .put("produced_at", "2026-11-18T01:10:00Z")
                .put("draft_id", req.path("draft_id").asText())
                .put("generation_id", "GEN-SENS-" + SEQ.incrementAndGet());
        ObjectNode inbound = body.putObject("facts").putObject("inbound").put("count", 2);
        inbound.putObject("amount").put("USD", AMOUNT_PLAIN);
        ((ObjectNode) body.get("facts")).put("subject_account", ACCOUNT_KEY);
        body.putObject("generation_meta").put("prompt_version", "narrative_v2");
        ArrayNode narrative = body.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", NARRATIVE + " " + s);
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(req.path("evidence_transaction_ids").get(0).asLong());
        }
        body.putNull("verification");
        return body;
    }

    /** 보낸 generation_id·revision_no 그대로, code_checks 에 금액·계좌 문장. */
    private static MockResponse verifyOk(RecordedRequest r) {
        JsonNode req = read(r);
        ObjectNode body = JSON.createObjectNode()
                .put("verification_id", "v-sens-" + SEQ.incrementAndGet())
                .put("generation_id", req.path("generation_id").asText())
                .put("revision_no", req.path("revision_no").asInt())
                .put("narrative_sha256", "0".repeat(64))
                .put("passed", false)
                .put("status", "CODE_FAILED");
        body.putArray("code_checks").addObject()
                .put("check", "AMOUNT_MATCH").put("section", StrSection.V2.get(0).name())
                .put("draft_value", AMOUNT_COMMA + " US Dollar " + ACCOUNT_KEY)
                .put("source_value", "inbound.amount.USD=" + AMOUNT_PLAIN)
                .put("match", false).put("reason", "MISMATCH " + EDITED);
        body.putNull("llm_checks");
        body.putNull("narrative_refs");
        body.putNull("judge_meta");
        body.put("verifier_version", "verify-test");
        body.put("produced_at", "2026-11-18T01:20:00Z");
        return json(200, body.toString());
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static JsonNode read(RecordedRequest r) {
        try {
            return JSON.readTree(r.getBody().clone().readUtf8());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- 도우미 -------------------------------------------------------------

    private void assertNoSensitive(CapturedOutput output) {
        String all = output.getAll().substring(mark);
        SoftAssertions soft = new SoftAssertions();
        for (String value : FORBIDDEN) {
            soft.assertThat(all).as("로그에 민감 값 [%s]", value).doesNotContain(value);
        }
        soft.assertAll();
    }

    private MvcResult createDraft(String caseNo) throws Exception {
        return mvc.perform(as(post("/api/v1/cases/" + caseNo + "/str-drafts"))
                .content(JSON.writeValueAsString(Map.of("case_version", version(caseNo))))).andReturn();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.with(user(new AppUserDetails(inv, "sens_inv01", "sens_inv01", "INVESTIGATOR"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static JsonNode ok(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return JSON.readTree(body);
    }

    private RecordedRequest lastAiRequest() throws InterruptedException {
        RecordedRequest last = null;
        for (RecordedRequest r; (r = AI.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS)) != null; ) last = r;
        return last;
    }

    private long version(String caseNo) {
        return jdbc.queryForObject("select version from ops.cases where case_no = ?", Long.class, caseNo);
    }

    /** 계좌 ACCOUNT_KEY·금액 AMOUNT 거래 2건이 든 경보(점수 있음) + inv 담당 ESCALATED 케이스. */
    private String escalatedCase() {
        long n = SEQ.incrementAndGet();
        String entity = "E_SENS_" + n;
        long rule = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, '테스트 룰', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_SENS_" + n);
        long alertId = insertAlert(jdbc, rule, "ALT-SENS-" + n, entity, "2022-09-02 23:59:59");
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, 0.9, 0.7, 'xgb-test', 'f1', ?, now(),
                        '{"reasons": [{"name": "account_in_counterparties_24h", "observation": {"value": 3}}]}'::jsonb)""",
                alertId, "p-sens-" + alertId);
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into ops.alert_transactions(alert_id, transaction_id) values (?, ?)", alertId, transaction());
        }
        String caseNo = "CASE-2022-%06d".formatted(800000 + n);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            Case c = Case.open(caseNo, alertId, ACCOUNT_KEY, entity, 1L);
            c.assign(inv, inv);
            c.escalate(inv, "STR 초안 작성 필요");
            Case saved = cases.save(c);
            histories.saveAll(saved.takePendingHistories());
        });
        return caseNo;
    }

    private static long insertAlert(JdbcTemplate jdbc, long rule, String alertNo, String entity, String referenceTs) {
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2022-09-02', 'RUNNING', 1) returning id""", Long.class);
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, ?, ?::timestamp - interval '1 day', ?::timestamp, ?::timestamp,
                        '{"inbound_count": 2}'::jsonb, '2022-09-02') returning id""", Long.class,
                alertNo, batchRunId, rule, ACCOUNT_KEY, entity, referenceTs, referenceTs, referenceTs);
    }

    private long transaction() {
        long n = SEQ.incrementAndGet();
        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('s.csv', ?) returning id",
                Long.class, String.format("%064d", 800000 + n));
        return jdbc.queryForObject("""
                insert into ops.transactions(ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values ('2022-09-02 10:00', '20', 'B', '20_B', '10', ?, ?, ?, 'US Dollar', ?, 'US Dollar',
                        'ACH', false, false, ?, 1, '020', '010') returning transaction_id""", Long.class,
                ACCOUNT_NO, ACCOUNT_KEY, new java.math.BigDecimal(AMOUNT_PLAIN), new java.math.BigDecimal(AMOUNT_PLAIN),
                fileId);
    }
}
