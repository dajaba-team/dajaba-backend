package com.team.aml.detection.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** GET /api/v1/admin/alerts R1~R3 (SCRUM-198) — 실제 PostgreSQL + 필터 체인 전체. */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AdminAlertApiTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAlertApiTest {

    static final String PASSWORD = "test-admin-alerts-pass-1234";
    static final String URL = "/api/v1/admin/alerts";
    private static final AtomicLong SEQ = new AtomicLong();
    private static final LocalDateTime REF = LocalDateTime.of(2022, 9, 3, 0, 0);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    // --- R1 목록 ---------------------------------------------------------------

    @Test
    void 정상_목록_한_줄은_정해진_칸뿐이고_원본_계좌_법인_정답_칸이_없다() throws Exception {
        Rule r = rule();
        Alert a = alert(r, "SCORED", null, false);
        score(a.id, "0.91");
        link(a.id, transaction());

        JsonNode body = json(call(admin(), URL, "rule_id", r.ruleId));

        assertThat(body.path("total").asLong()).isEqualTo(1);
        JsonNode item = body.path("items").get(0);
        assertThat(keys(item)).containsExactlyInAnyOrder("alert_no", "batch_date", "rule_id", "rule_version",
                "typology_hint", "account_no", "account_ref", "window_start", "window_end", "reference_ts",
                "transaction_count", "score_status", "score_failure_code", "retryable", "latest_score", "case_opened",
                "created_at");
        assertThat(item.path("alert_no").asText()).isEqualTo(a.alertNo);
        assertThat(item.path("account_no").asText()).isEqualTo("10_****" + a.accountKey.substring(a.accountKey.length() - 4));
        assertThat(item.path("account_ref").asText()).matches("ACR-[0-9A-F]{12}");
        assertThat(item.path("transaction_count").asInt()).isEqualTo(1);
        assertThat(item.path("latest_score").path("score").decimalValue()).isEqualByComparingTo("0.91");
        assertThat(keys(item.path("latest_score")))
                .containsExactlyInAnyOrder("score", "threshold", "data_quality_status", "model_version");
        assertThat(body.toString()).doesNotContain(a.accountKey).doesNotContain("is_laundering")
                .doesNotContain("entity_id");
    }

    @Test
    void 정상_필터는_전부_AND() throws Exception {
        Rule r = rule();
        Alert scored = alert(r, "SCORED", null, true);
        score(scored.id, "0.5");
        Alert failed = alert(r, "FAILED", "COMPUTE_ERROR", false);
        alert(r, "PENDING", null, false);

        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "score_status", "FAILED")))
                .containsExactly(failed.alertNo);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "score_failure_code", "COMPUTE_ERROR")))
                .containsExactly(failed.alertNo);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "case_opened", "true")))
                .containsExactly(scored.alertNo);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "date", "2022-09-03"))).hasSize(3);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "date", "2022-09-04"))).isEmpty();
    }

    @Test
    void 정상_기본_정렬은_점수_내림차순이고_점수_없는_경보는_오름_내림_모두_맨_뒤() throws Exception {
        Rule r = rule();
        Alert low = alert(r, "SCORED", null, false);
        score(low.id, "0.2");
        Alert none = alert(r, "FAILED", null, false);
        Alert high = alert(r, "SCORED", null, false);
        score(high.id, "0.9");

        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId)))
                .containsExactly(high.alertNo, low.alertNo, none.alertNo);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "sort", "score,asc")))
                .containsExactly(low.alertNo, high.alertNo, none.alertNo);
        assertThat(alertNos(call(admin(), URL, "rule_id", r.ruleId, "sort", "alert_no,asc")))
                .containsExactly(low.alertNo, none.alertNo, high.alertNo);
    }

    @Test
    void 정상_FAILED도_목록에_남고_latest_score는_null이며_retryable은_코드_NULL_COMPUTE_ERROR만() throws Exception {
        Rule r = rule();
        Alert noCode = alert(r, "FAILED", null, false);
        Alert compute = alert(r, "FAILED", "COMPUTE_ERROR", false);
        Alert notFound = alert(r, "FAILED", "TRANSACTION_NOT_FOUND", false);
        Alert pending = alert(r, "PENDING", null, false);

        JsonNode items = json(call(admin(), URL, "rule_id", r.ruleId, "sort", "alert_no,asc")).path("items");

        assertThat(items).hasSize(4);
        Map<String, Boolean> retryable = new java.util.HashMap<>();
        items.forEach(i -> {
            assertThat(i.path("latest_score").isNull()).isTrue();
            retryable.put(i.path("alert_no").asText(), i.path("retryable").asBoolean());
        });
        assertThat(retryable).containsEntry(noCode.alertNo, true).containsEntry(compute.alertNo, true)
                .containsEntry(notFound.alertNo, false).containsEntry(pending.alertNo, false);
    }

    @Test
    void 정상_목록은_열람_기록을_남기지_않는다() throws Exception {
        Rule r = rule();
        Alert a = alert(r, "PENDING", null, false);

        call(admin(), URL, "rule_id", r.ruleId);

        assertThat(viewCount(a.id)).isZero();
    }

    // --- R2 상세 ---------------------------------------------------------------

    @Test
    void 정상_상세는_rule_facts와_점수_세부를_주고_열람_기록_1행() throws Exception {
        Rule r = rule();
        Alert a = alert(r, "SCORED", null, false);
        score(a.id, "0.91");

        MvcResult res = call(admin(), URL + "/" + a.alertNo);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(body.path("rule_facts").path("distinct_senders").asInt()).isEqualTo(31);
        JsonNode s = body.path("latest_score");
        assertThat(s.path("feature_version").asText()).isEqualTo("f1");
        assertThat(s.path("prediction_id").asText()).isEqualTo("p-" + a.id);
        assertThat(s.path("produced_at").asText()).endsWith("Z");
        assertThat(s.path("reasons").get(0).path("name").asText()).isEqualTo("account_in_counterparties_24h");
        assertThat(s.path("evidence_transaction_ids").isArray()).isTrue();
        assertThat(s.path("context_transaction_ids").isArray()).isTrue();
        assertThat(body.toString()).doesNotContain(a.accountKey);
        assertThat(viewCount(a.id)).isEqualTo(1);
    }

    @Test
    void 정상_FAILED_상세는_latest_score_null이고_실패_코드가_보인다() throws Exception {
        Rule r = rule();
        Alert a = alert(r, "FAILED", "TRANSACTION_NOT_FOUND", false);

        JsonNode body = json(call(admin(), URL + "/" + a.alertNo));

        assertThat(body.path("latest_score").isNull()).isTrue();
        assertThat(body.path("score_status").asText()).isEqualTo("FAILED");
        assertThat(body.path("score_failure_code").asText()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(body.path("retryable").asBoolean()).isFalse();
    }

    // --- R3 거래 ---------------------------------------------------------------

    @Test
    void 정상_거래는_가린_계좌와_account_ref만이고_열람_기록_1행() throws Exception {
        Rule r = rule();
        Alert a = alert(r, "PENDING", null, false);
        long t1 = transaction();
        long t2 = transaction();
        link(a.id, t1);
        link(a.id, t2);

        MvcResult res = call(admin(), URL + "/" + a.alertNo + "/transactions", "sort", "transaction_id,desc");

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(res);
        assertThat(body.path("total").asLong()).isEqualTo(2);
        JsonNode first = body.path("items").get(0);
        assertThat(first.path("transaction_id").asLong()).isEqualTo(t2);
        assertThat(keys(first)).containsExactlyInAnyOrder("transaction_id", "from_account_ref", "from_account_no",
                "to_account_ref", "to_account_no", "ts", "amount_paid", "currency_paid", "amount_received",
                "currency_received", "payment_format");
        assertThat(first.path("from_account_no").asText()).isEqualTo("10_****");
        assertThat(body.toString()).doesNotContain("10_A").doesNotContain("is_laundering");
        assertThat(viewCount(a.id)).isEqualTo(1);
    }

    // --- 실패 -----------------------------------------------------------------

    @Test
    void 실패_없는_alert_no는_404_ALERT_NOT_FOUND이고_기록을_남기지_않는다() throws Exception {
        long before = jdbc.queryForObject("select count(*) from ops.access_logs where target_type = 'ALERT'", Long.class);

        for (String path : List.of(URL + "/ALT-19990101-000001", URL + "/ALT-19990101-000001/transactions")) {
            MvcResult r = call(admin(), path);
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(code(r)).isEqualTo("ALERT_NOT_FOUND");
            assertThat(json(r).path("error").path("details").path("alert_no").asText()).isEqualTo("ALT-19990101-000001");
        }
        assertThat(jdbc.queryForObject("select count(*) from ops.access_logs where target_type = 'ALERT'", Long.class))
                .isEqualTo(before);
    }

    @Test
    void 실패_INVESTIGATOR와_APPROVER는_403_비로그인은_401() throws Exception {
        for (String loginId : List.of("inv01", "apr01")) {
            MvcResult r = mvc.perform(get(URL).session(session(login(loginId)))).andReturn();
            assertThat(r.getResponse().getStatus()).as(loginId).isEqualTo(403);
            assertThat(code(r)).isEqualTo("FORBIDDEN");
        }
        MvcResult anonymous = mvc.perform(get(URL)).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 실패_모르는_값_정렬_size_초과는_400() throws Exception {
        for (String[] q : List.of(new String[]{"score_status", "DONE"}, new String[]{"score_failure_code", "X"},
                new String[]{"sort", "account_key,asc"}, new String[]{"size", "101"},
                new String[]{"rule_id", "r02;drop"}, new String[]{"date", "2022-13-01"})) {
            MvcResult r = call(admin(), URL, q);
            assertThat(r.getResponse().getStatus()).as(q[0]).isEqualTo(400);
            assertThat(code(r)).isEqualTo("INVALID_REQUEST");
        }
    }

    // --- 데이터 ----------------------------------------------------------------

    private record Rule(long id, String ruleId) {}

    private record Alert(long id, String alertNo, String accountKey) {}

    private Rule rule() {
        String ruleId = "R_T198_" + SEQ.incrementAndGet();
        long id = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, '테스트 룰', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class, ruleId);
        return new Rule(id, ruleId);
    }

    /** 창 [REF-1일, REF], 계좌는 매번 새로(끝 4자리가 보이도록 8자리 번호). */
    private Alert alert(Rule rule, String status, String failureCode, boolean caseOpened) {
        long n = SEQ.incrementAndGet();
        String alertNo = "ALT-20220903-%06d".formatted(900000 + n);
        String accountKey = "10_%08d".formatted(n);
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, REF.toLocalDate());
        long id = jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                       reference_ts, rule_facts, batch_date, score_status, score_failure_code,
                                       case_opened)
                values (?, ?, ?, ?, ?, ?, ?, '{"distinct_senders": 31}'::jsonb, ?, ?, ?, ?) returning id""",
                Long.class, alertNo, batchRunId, rule.id, accountKey, REF.minusDays(1), REF, REF,
                REF.toLocalDate(), status, failureCode, caseOpened);
        return new Alert(id, alertNo, accountKey);
    }

    private void score(long alertId, String score) {
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version, prediction_id,
                                             produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), ?::jsonb)""",
                alertId, score, "p-" + alertId, """
                {"data_quality_status": "OK", "reasons": [{"name": "account_in_counterparties_24h"}],
                 "evidence_transaction_ids": [], "context_transaction_ids": []}""");
    }

    private long transaction() {
        long n = SEQ.incrementAndGet();
        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('t.csv', ?) returning id",
                Long.class, "%064d".formatted(198_000_000L + n));
        return jdbc.queryForObject("""
                insert into ops.transactions(ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values ('2022-09-02 10:00', '10', 'A', '10_A', '20', 'B', '20_B', 1, 'US Dollar', 1, 'US Dollar',
                        'ACH', true, false, ?, 1, '010', '020') returning transaction_id""", Long.class, fileId);
    }

    private void link(long alertId, long transactionId) {
        jdbc.update("insert into ops.alert_transactions(alert_id, transaction_id) values (?, ?)", alertId, transactionId);
    }

    private long viewCount(long alertId) {
        return jdbc.queryForObject("""
                select count(*) from ops.access_logs where action = 'VIEW' and target_type = 'ALERT' and target_id = ?""",
                Long.class, alertId);
    }

    // --- 호출 ------------------------------------------------------------------

    private MvcResult call(MockHttpSession session, String path, String... nameValues) throws Exception {
        var req = get(path).session(session);
        for (int i = 0; i < nameValues.length; i += 2) req.param(nameValues[i], nameValues[i + 1]);
        return mvc.perform(req).andReturn();
    }

    private MockHttpSession admin() throws Exception {
        return session(login("admin"));
    }

    private MvcResult login(String loginId) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", PASSWORD)))).andReturn();
    }

    private static MockHttpSession session(MvcResult r) {
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }

    private List<String> alertNos(MvcResult r) throws Exception {
        List<String> nos = new ArrayList<>();
        json(r).path("items").forEach(i -> nos.add(i.path("alert_no").asText()));
        return nos;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
