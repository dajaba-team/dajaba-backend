package com.team.aml.cases.api;

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
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/v1/admin/alerts/stats (SCRUM-360) — 관리자 대시보드 경보 위험 등급 집계. 실제 PostgreSQL + 필터 체인 전체.
 * 등급 경계값 0.5 / 0.9 — score ≥ 0.9 HIGH, ≥ 0.5 MEDIUM, 그 외 LOW(CasePolicy.classify 와 같은 규칙).
 * 정책이 없을 때(bands null)는 GetAlertRiskStatsTest(DB 없이).
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.dev-users.enabled=true",
        "aml.dev-users.password=" + AlertStatsApiTest.PASSWORD,
        "aml.case-policy.version=test-v1",
        "aml.case-policy.low-to-medium=0.5",
        "aml.case-policy.medium-to-high=0.9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertStatsApiTest {

    static final String PASSWORD = "test-alert-stats-pass-1234";
    private static final String URL = "/api/v1/admin/alerts/stats";
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
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 정상_기준일_경보를_운영_등급으로_나누고_대기_실패_케이스_전환을_센다() throws Exception {
        LocalDate d = newDate();
        long rule = rule();
        scored(rule, d, "0.95", true);    // HIGH, 케이스 전환
        scored(rule, d, "0.70", false);   // MEDIUM
        scored(rule, d, "0.20", false);   // LOW
        alert(rule, d, "PENDING", null, false);
        alert(rule, d, "FAILED", null, false);
        scored(rule, d.plusDays(1), "0.99", true);   // 다른 날 — 세지 않음

        JsonNode body = json(call(admin(), "date", d.toString()), 200);

        assertThat(body.get("date").asText()).isEqualTo(d.toString());
        assertThat(body.get("total").asLong()).isEqualTo(5);
        assertThat(body.get("scored").asLong()).isEqualTo(3);
        assertThat(body.get("bands").get("high").asLong()).isEqualTo(1);
        assertThat(body.get("bands").get("medium").asLong()).isEqualTo(1);
        assertThat(body.get("bands").get("low").asLong()).isEqualTo(1);
        assertThat(body.get("unscored").asLong()).isEqualTo(1);
        assertThat(body.get("failed").asLong()).isEqualTo(1);
        assertThat(body.get("case_opened").asLong()).isEqualTo(1);
        assertThat(body.get("policy_version").asText()).isEqualTo("test-v1");
    }

    @Test
    void 경계_경계값과_같은_점수는_위_등급이고_날짜를_빼면_전체를_센다() throws Exception {
        LocalDate d = newDate();
        long rule = rule();
        scored(rule, d, "0.9", false);    // = medium_to_high → HIGH
        scored(rule, d, "0.5", false);    // = low_to_medium → MEDIUM
        scored(rule, d, "0.4999", false); // LOW

        JsonNode day = json(call(admin(), "date", d.toString()), 200);
        assertThat(day.get("bands").get("high").asLong()).isEqualTo(1);
        assertThat(day.get("bands").get("medium").asLong()).isEqualTo(1);
        assertThat(day.get("bands").get("low").asLong()).isEqualTo(1);

        JsonNode all = json(call(admin()), 200);
        assertThat(all.get("date").isNull()).isTrue();
        assertThat(all.get("total").asLong()).isGreaterThanOrEqualTo(3);   // 다른 테스트 경보까지 전부
        long bandSum = all.get("bands").get("high").asLong() + all.get("bands").get("medium").asLong()
                + all.get("bands").get("low").asLong();
        assertThat(bandSum).isEqualTo(all.get("scored").asLong());   // 점수 있는 경보만 나눔
    }

    @Test
    void 경계_경보가_없는_날은_모두_0이다() throws Exception {
        JsonNode body = json(call(admin(), "date", "2030-01-01"), 200);
        assertThat(body.get("total").asLong()).isZero();
        assertThat(body.get("bands").get("high").asLong()).isZero();
    }

    @Test
    void 실패_관리자만_로그인해야_날짜_형식이_맞아야() throws Exception {
        assertThat(mvc.perform(get(URL)).andReturn().getResponse().getStatus()).isEqualTo(401);
        MvcResult investigator = call(session(login("inv01")));
        assertThat(investigator.getResponse().getStatus()).isEqualTo(403);
        MvcResult bad = call(admin(), "date", "2022/09/08");
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad, 400).path("error").path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    // --- 준비 ------------------------------------------------------------------

    private static LocalDate newDate() {
        return LocalDate.of(2023, 1, 1).plusDays(SEQ.incrementAndGet() * 3);
    }

    private long rule() {
        return jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, '테스트 룰', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class,
                "R_T360_" + SEQ.incrementAndGet());
    }

    private long alert(long ruleId, LocalDate date, String status, String failureCode, boolean caseOpened) {
        long n = SEQ.incrementAndGet();
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, date);
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                       reference_ts, rule_facts, batch_date, score_status, score_failure_code,
                                       case_opened)
                values (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?) returning id""",
                Long.class, "ALT-T360-%06d".formatted(n), batchRunId, ruleId, "10_%08d".formatted(n),
                date.atStartOfDay().minusDays(1), date.atStartOfDay(), date.atStartOfDay(), date, status,
                failureCode, caseOpened);
    }

    private void scored(long ruleId, LocalDate date, String score, boolean caseOpened) {
        long alertId = alert(ruleId, date, "SCORED", null, caseOpened);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version, prediction_id,
                                             produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"data_quality_status": "OK"}'::jsonb)""",
                alertId, score, "p-" + alertId);
    }

    // --- 호출 ------------------------------------------------------------------

    private MvcResult call(MockHttpSession session, String... nameValues) throws Exception {
        var req = get(URL).session(session);
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

    private JsonNode json(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(expectedStatus);
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
