package com.team.aml.cases.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 케이스 근거 거래·원본·그래프·이력·aggregates (SCRUM-179, 계약서 C2·C3·C4·C5·C6). 실제 PostgreSQL.
 * 대상 계좌 A 기준 거래 5건: B→A, C→A(유입) · A→D(유출, 다른 통화) · D→E(경유) · A→A(자기계좌).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseTransactionsApiTest {

    private static final AtomicLong SEQ = new AtomicLong();

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
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private AppUserDetails inv;
    private AppUserDetails apr;

    @BeforeEach
    void users() {
        long n = SEQ.incrementAndGet();
        inv = newUser("tx_inv" + n, "INVESTIGATOR");
        apr = newUser("tx_apr" + n, "APPROVER");
    }

    // ── C3 근거 거래 ─────────────────────────────────────────

    @Test
    void 근거_거래는_케이스_계좌_기준_방향과_가린_계좌로_시각순() throws Exception {
        Fixture f = caseWithTransactions();

        String body = body(get("/api/v1/cases/{caseNo}/transactions", f.caseNo).with(user(apr)), 200);
        JsonNode page = json.readTree(body);
        assertThat(page.get("total").asLong()).isEqualTo(5);
        List<String> directions = new ArrayList<>();
        page.get("items").forEach(i -> directions.add(i.get("direction").asText()));
        assertThat(directions).containsExactly("INBOUND", "INBOUND", "OUTBOUND", "INDIRECT", "INBOUND");
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("transaction_id").asLong()).isEqualTo(f.ids[0]);
        assertThat(first.get("to_account_no").asText()).isEqualTo("010_****0001");
        assertThat(first.get("to_account_ref").asText()).matches("ACR-[0-9A-F]{12}");
        assertThat(first.get("ts").asText()).isEqualTo("2023-03-01T09:00:00");
        assertThat(first.get("amount_received").decimalValue()).isEqualByComparingTo("600");
        assertThat(first.get("currency_received").asText()).isEqualTo("US Dollar");
        assertThat(first.get("payment_format").asText()).isEqualTo("ACH");
        assertThat(first.get("scope").asText()).isEqualTo("EVIDENCE");
        assertThat(body).doesNotContain(f.accountKey).doesNotContain("account_key")
                .doesNotContain("is_laundering").doesNotContain("pattern");

        JsonNode desc = json.readTree(body(get("/api/v1/cases/{caseNo}/transactions", f.caseNo)
                .param("sort", "transaction_id,desc").param("size", "2").param("page", "1").with(user(inv)), 200));
        assertThat(desc.get("total").asLong()).isEqualTo(5);
        assertThat(desc.get("items")).hasSize(2);
        assertThat(desc.get("items").get(0).get("transaction_id").asLong()).isEqualTo(f.ids[2]);

        // 참고 거래는 저장소가 아직 없어 빈 목록
        JsonNode context = json.readTree(body(get("/api/v1/cases/{caseNo}/transactions", f.caseNo)
                .param("scope", "CONTEXT").with(user(inv)), 200));
        assertThat(context.get("items")).isEmpty();
        assertThat(context.get("total").asLong()).isZero();
    }

    @Test
    void 근거_거래_형식_오류는_400_없는_케이스는_404() throws Exception {
        Fixture f = caseWithTransactions();
        assertThat(code(get("/api/v1/cases/{caseNo}/transactions", f.caseNo).param("sort", "amount,asc").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases/{caseNo}/transactions", f.caseNo).param("size", "101").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases/{caseNo}/transactions", f.caseNo).param("scope", "ALL").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases/{caseNo}/transactions", "CASE-2099-999999").with(user(inv)), 404))
                .isEqualTo("CASE_NOT_FOUND");
        assertThat(mvc.perform(get("/api/v1/cases/{caseNo}/transactions", f.caseNo)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    // ── C4 원본 ──────────────────────────────────────────────

    @Test
    void 원본은_이_케이스의_근거_거래만_다른_케이스_거래는_404_열람_기록() throws Exception {
        Fixture f = caseWithTransactions();
        Fixture other = caseWithTransactions();
        long before = viewLogs(f.caseId);

        JsonNode e = json.readTree(body(get("/api/v1/cases/{caseNo}/evidence/{id}", f.caseNo, f.ids[2]).with(user(inv)), 200));
        assertThat(e.get("case_no").asText()).isEqualTo(f.caseNo);
        assertThat(e.get("transaction").get("direction").asText()).isEqualTo("OUTBOUND");
        assertThat(e.get("transaction").get("from_account_no").asText()).isEqualTo("010_****0001");
        assertThat(e.get("source").get("dataset").asText()).isEqualTo("IBM AML HI-Small");
        assertThat(e.get("source").get("record_id").asLong()).isEqualTo(f.ids[2]);
        assertThat(e.get("source").get("is_synthetic").asBoolean()).isTrue();
        assertThat(e.get("eligible_for_str").asBoolean()).isTrue();
        assertThat(viewLogs(f.caseId)).isEqualTo(before + 1);

        assertThat(code(get("/api/v1/cases/{caseNo}/evidence/{id}", f.caseNo, other.ids[0]).with(user(inv)), 404))
                .isEqualTo("EVIDENCE_NOT_FOUND");
        assertThat(code(get("/api/v1/cases/{caseNo}/evidence/{id}", f.caseNo, 999_999_999L).with(user(inv)), 404))
                .isEqualTo("EVIDENCE_NOT_FOUND");
        assertThat(code(get("/api/v1/cases/{caseNo}/evidence/{id}", "CASE-2099-999999", f.ids[0]).with(user(inv)), 404))
                .isEqualTo("CASE_NOT_FOUND");
        assertThat(viewLogs(f.caseId)).isEqualTo(before + 1);   // 404 는 기록 안 함
    }

    // ── C5 그래프 ────────────────────────────────────────────

    @Test
    void 그래프는_계좌_중복_없이_거래마다_선_limit_넘으면_truncated() throws Exception {
        Fixture f = caseWithTransactions();

        JsonNode all = json.readTree(body(get("/api/v1/cases/{caseNo}/graph", f.caseNo).with(user(inv)), 200));
        assertThat(all.get("truncated").asBoolean()).isFalse();
        assertThat(all.get("returned_transaction_count").asInt()).isEqualTo(5);
        assertThat(all.get("total_transaction_count").asLong()).isEqualTo(5);
        assertThat(all.get("nodes")).hasSize(5);   // A·B·C·D·E
        JsonNode edge = all.get("edges").get(0);
        assertThat(edge.get("transaction_id").asLong()).isEqualTo(f.ids[0]);
        assertThat(edge.get("target").asText()).isEqualTo(all.get("nodes").get(1).get("account_ref").asText());
        assertThat(edge.get("amount_basis").asText()).isEqualTo("RECEIVED");
        assertThat(edge.get("scope").asText()).isEqualTo("EVIDENCE");
        assertThat(all.toString()).doesNotContain(f.accountKey);

        JsonNode cut = json.readTree(body(get("/api/v1/cases/{caseNo}/graph", f.caseNo).param("limit", "3").with(user(inv)), 200));
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        assertThat(cut.get("returned_transaction_count").asInt()).isEqualTo(3);
        assertThat(cut.get("total_transaction_count").asLong()).isEqualTo(5);

        assertThat(code(get("/api/v1/cases/{caseNo}/graph", f.caseNo).param("limit", "0").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases/{caseNo}/graph", f.caseNo).param("limit", "1001").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 그래프_기본_200건을_넘으면_truncated() throws Exception {
        Fixture f = newCase();
        long fileId = sourceFile();
        jdbc.update("""
                with t as (
                    insert into ops.transactions (ts, from_bank_id, from_account_no, from_account_key,
                        to_bank_id, to_account_no, to_account_key, amount_received, currency_received, amount_paid,
                        currency_paid, payment_format, is_laundering, is_self_transfer, source_file_id,
                        source_row_number, from_bank_id_raw, to_bank_id_raw)
                    select timestamp '2023-03-01 00:00:00' + g * interval '1 minute', '020', 'S' || g, '020_S' || g,
                           '010', ?, ?, 10, 'US Dollar', 10, 'US Dollar', 'ACH', false, false, ?, g, '020', '010'
                    from generate_series(1, 201) g
                    returning transaction_id)
                insert into ops.alert_transactions (alert_id, transaction_id) select ?, transaction_id from t""",
                f.accountNo, f.accountKey, fileId, f.alertId);

        JsonNode g = json.readTree(body(get("/api/v1/cases/{caseNo}/graph", f.caseNo).with(user(inv)), 200));
        assertThat(g.get("truncated").asBoolean()).isTrue();
        assertThat(g.get("returned_transaction_count").asInt()).isEqualTo(200);
        assertThat(g.get("total_transaction_count").asLong()).isEqualTo(201);
    }

    // ── C2 aggregates ────────────────────────────────────────

    @Test
    void 상세_aggregates는_통화별_유입_유출이고_경유_자기계좌_거래는_합계에서_뺀다() throws Exception {
        Fixture f = caseWithTransactions();

        JsonNode agg = json.readTree(body(get("/api/v1/cases/{caseNo}", f.caseNo).with(user(inv)), 200)).get("aggregates");
        assertThat(agg.get("transaction_count").asInt()).isEqualTo(5);
        assertThat(agg.get("inbound").get("transaction_count").asInt()).isEqualTo(2);
        assertThat(agg.get("inbound").get("totals")).hasSize(1);
        assertThat(agg.get("inbound").get("totals").get(0).get("currency").asText()).isEqualTo("US Dollar");
        assertThat(agg.get("inbound").get("totals").get(0).get("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(agg.get("outbound").get("transaction_count").asInt()).isEqualTo(1);
        assertThat(agg.get("outbound").get("totals").get(0).get("currency").asText()).isEqualTo("Euro");
        assertThat(agg.get("outbound").get("totals").get(0).get("amount").decimalValue()).isEqualByComparingTo("300");
        assertThat(agg.get("first_ts").asText()).isEqualTo("2023-03-01T09:00:00");
        assertThat(agg.get("last_ts").asText()).isEqualTo("2023-03-01T13:00:00");
    }

    @Test
    void 근거_거래가_없는_케이스는_빈_목록_빈_그래프_0건_합계() throws Exception {
        Fixture f = newCase();

        JsonNode page = json.readTree(body(get("/api/v1/cases/{caseNo}/transactions", f.caseNo).with(user(inv)), 200));
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("total").asLong()).isZero();
        JsonNode g = json.readTree(body(get("/api/v1/cases/{caseNo}/graph", f.caseNo).with(user(inv)), 200));
        assertThat(g.get("nodes")).isEmpty();
        assertThat(g.get("edges")).isEmpty();
        assertThat(g.get("truncated").asBoolean()).isFalse();
        JsonNode agg = json.readTree(body(get("/api/v1/cases/{caseNo}", f.caseNo).with(user(inv)), 200)).get("aggregates");
        assertThat(agg.get("transaction_count").asInt()).isZero();
        assertThat(agg.get("inbound").get("totals")).isEmpty();
        assertThat(agg.get("first_ts").isNull()).isTrue();
    }

    // ── C6 이력 ──────────────────────────────────────────────

    @Test
    void 이력_전체는_최신_먼저_NOTE_빼고_action_필터() throws Exception {
        Fixture f = newCase();
        body(post("/api/v1/cases/{caseNo}/assign", f.caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0}").with(user(inv)).with(csrf()), 200);
        body(put("/api/v1/cases/{caseNo}/notes", f.caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"investigator_notes\": \"메모\", \"version\": 1}").with(user(inv)).with(csrf()), 200);
        body(post("/api/v1/cases/{caseNo}/escalate", f.caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"상신 사유\", \"version\": 2}").with(user(inv)).with(csrf()), 200);

        JsonNode page = json.readTree(body(get("/api/v1/cases/{caseNo}/histories", f.caseNo).with(user(apr)), 200));
        List<String> actions = new ArrayList<>();
        page.get("items").forEach(i -> actions.add(i.get("action").asText()));
        assertThat(actions).containsExactly("ESCALATE", "ASSIGN", "OPEN");
        assertThat(page.get("total").asLong()).isEqualTo(3);
        JsonNode top = page.get("items").get(0);
        assertThat(top.get("comment").asText()).isEqualTo("상신 사유");
        assertThat(top.get("from_status").asText()).isEqualTo("REVIEWING");
        assertThat(top.get("created_by").asText()).isEqualTo(inv.loginId());
        assertThat(top.get("created_at").asText()).endsWith("Z");

        JsonNode only = json.readTree(body(get("/api/v1/cases/{caseNo}/histories", f.caseNo)
                .param("action", "ASSIGN").with(user(inv)), 200));
        assertThat(only.get("items")).hasSize(1);
        JsonNode paged = json.readTree(body(get("/api/v1/cases/{caseNo}/histories", f.caseNo)
                .param("size", "1").param("page", "2").with(user(inv)), 200));
        assertThat(paged.get("items").get(0).get("action").asText()).isEqualTo("OPEN");

        assertThat(code(get("/api/v1/cases/{caseNo}/histories", f.caseNo).param("action", "NOTE").with(user(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases/{caseNo}/histories", "CASE-2099-999999").with(user(inv)), 404))
                .isEqualTo("CASE_NOT_FOUND");
    }

    // ── 준비 ──────────────────────────────────────────────────

    private record Fixture(String caseNo, long caseId, long alertId, String accountKey, String accountNo, long[] ids) {
    }

    /** 대상 계좌 A 의 케이스 + 거래 5건(시각순 = ids 순). transaction_id 는 DB 가 발급한다(V004_1). */
    private Fixture caseWithTransactions() {
        Fixture f = newCase();
        long n = SEQ.incrementAndGet();
        String a = f.accountKey;
        String b = "020_B" + n, c = "030_C" + n, d = "040_D" + n, e = "050_E" + n;
        long[] ids = {
                tx("2023-03-01 09:00:00", b, a, "600", "US Dollar", "600", "US Dollar"),
                tx("2023-03-01 10:00:00", c, a, "400", "US Dollar", "400", "US Dollar"),
                tx("2023-03-01 11:00:00", a, d, "320", "US Dollar", "300", "Euro"),
                tx("2023-03-01 12:00:00", d, e, "100", "US Dollar", "100", "US Dollar"),
                tx("2023-03-01 13:00:00", a, a, "50", "US Dollar", "50", "US Dollar")};
        for (long id : ids) {
            jdbc.update("insert into ops.alert_transactions (alert_id, transaction_id) values (?, ?)", f.alertId, id);
        }
        return new Fixture(f.caseNo, f.caseId, f.alertId, f.accountKey, f.accountNo, ids);
    }

    private long sourceFile() {
        return jdbc.queryForObject("insert into ops.source_files(file_name, file_hash) values ('t.csv', ?) returning id",
                Long.class, String.format("%064d", 700000 + SEQ.incrementAndGet()));
    }

    private long tx(String ts, String from, String to, String received, String receivedCurrency,
                    String paid, String paidCurrency) {
        return jdbc.queryForObject("""
                insert into ops.transactions (ts, from_bank_id, from_account_no, from_account_key,
                    to_bank_id, to_account_no, to_account_key, amount_received, currency_received, amount_paid,
                    currency_paid, payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values (?::timestamp, ?, ?, ?, ?, ?, ?, ?::numeric, ?, ?::numeric, ?, 'ACH', true, ?, ?, 1, ?, ?)
                returning transaction_id""", Long.class,
                ts, from.substring(0, 3), from.substring(4), from, to.substring(0, 3), to.substring(4), to,
                received, receivedCurrency, paid, paidCurrency, from.equals(to), sourceFile(),
                from.substring(0, 3), to.substring(0, 3));
    }

    private Fixture newCase() {
        long n = SEQ.incrementAndGet();
        String accountNo = "A%05d0001".formatted(n);
        String accountKey = "010_" + accountNo;
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-03-01', 'RUNNING', 1) returning id""", Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_TX_" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-03-01 00:00:00', '2023-03-01 23:59:59', '2023-03-01 23:59:59',
                        '{}'::jsonb, '2023-03-01')
                returning id""", Long.class, "ALT-20230301-%06d".formatted(n), batchId, ruleId, accountKey);
        String caseNo = "CASE-2023-%06d".formatted(700000 + n);
        long caseId = jdbc.queryForObject("""
                insert into ops.cases (case_no, alert_id, account_key, status, created_by, updated_by)
                values (?, ?, ?, 'OPEN', 1, 1) returning id""", Long.class, caseNo, alertId, accountKey);
        jdbc.update("""
                insert into ops.case_history (case_id, action, to_status, case_version_before, created_by)
                values (?, 'OPEN', 'OPEN', 0, 1)""", caseId);
        return new Fixture(caseNo, caseId, alertId, accountKey, accountNo, new long[0]);
    }

    private long viewLogs(long caseId) {
        return jdbc.queryForObject("""
                select count(*) from ops.access_logs where action = 'VIEW' and target_type = 'CASE' and target_id = ?""",
                Long.class, caseId);
    }

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        response.setCharacterEncoding("UTF-8");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return response.getContentAsString();
    }

    private String code(RequestBuilder request, int expectedStatus) throws Exception {
        return json.readTree(body(request, expectedStatus)).get("error").get("code").asText();
    }

    private AppUserDetails newUser(String loginId, String role) {
        long id = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1) returning id""", Long.class, loginId, "이름 " + loginId, role);
        return new AppUserDetails(id, loginId, "이름 " + loginId, role);
    }
}
