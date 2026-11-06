package com.team.aml.cases.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** POST /api/v1/admin/batches/open-cases — 202 로 받고 뒤에서 케이스를 만든다 (SCRUM-105, 계약서 B4). */
@Testcontainers
@SpringBootTest(properties = {
        "aml.case-policy.version=test-v1",
        "aml.case-policy.low-to-medium=0.5",
        "aml.case-policy.medium-to-high=0.9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenCasesApiTest {

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

    @Test
    void 정상_202_RUNNING으로_받고_뒤에서_HIGH만_케이스를_만든다() throws Exception {
        LocalDate date = LocalDate.of(2022, 10, 1);
        long high = scoredAlert(date, "0.95");
        long low = scoredAlert(date, "0.1");

        JsonNode accepted = body(mvc.perform(post("/api/v1/admin/batches/open-cases").param("date", date.toString())
                .with(admin()).with(csrf())).andReturn(), 202);
        long runId = accepted.get("batch_run_id").asLong();
        assertThat(accepted.get("batch_type").asText()).isEqualTo("OPEN_CASES");
        assertThat(accepted.get("batch_date").asText()).isEqualTo("2022-10-01");
        assertThat(accepted.get("status").asText()).isEqualTo("RUNNING");

        Map<String, Object> run = waitUntilFinished(runId);
        assertThat(run.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) run.get("rows_in")).intValue()).isEqualTo(2);
        assertThat(((Number) run.get("rows_out")).intValue()).isEqualTo(1);
        assertThat(((Number) run.get("triggered_by")).longValue()).isEqualTo(1L);
        assertThat(jdbc.queryForList("select alert_id from ops.cases where alert_id in (?, ?)", Long.class, high, low))
                .containsExactly(high);
    }

    @Test
    void 실패_이미_성공한_날짜는_409_BATCH_ALREADY_DONE() throws Exception {
        LocalDate date = LocalDate.of(2022, 10, 2);
        scoredAlert(date, "0.95");
        long runId = body(mvc.perform(post("/api/v1/admin/batches/open-cases").param("date", date.toString())
                .with(admin()).with(csrf())).andReturn(), 202).get("batch_run_id").asLong();
        waitUntilFinished(runId);

        JsonNode error = body(mvc.perform(post("/api/v1/admin/batches/open-cases").param("date", date.toString())
                .with(admin()).with(csrf())).andReturn(), 409);
        assertThat(error.get("error").get("code").asText()).isEqualTo("BATCH_ALREADY_DONE");
    }

    @Test
    void 실패_ADMIN이_아니거나_CSRF가_없으면_배치_행을_만들지_않는다() throws Exception {
        LocalDate date = LocalDate.of(2022, 10, 3);
        assertThat(status(post("/api/v1/admin/batches/open-cases").param("date", date.toString())
                .with(investigator()).with(csrf()))).isEqualTo(403);
        assertThat(status(post("/api/v1/admin/batches/open-cases").param("date", date.toString())
                .with(admin()))).isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "select count(*) from ops.batch_runs where batch_type = 'OPEN_CASES' and batch_date = ?",
                Long.class, date)).isZero();
    }

    private Map<String, Object> waitUntilFinished(long runId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> run = jdbc.queryForMap(
                    "select status, rows_in, rows_out, triggered_by from ops.batch_runs where id = ?", runId);
            if (!"RUNNING".equals(run.get("status"))) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("케이스 생성이 20초 안에 끝나지 않았습니다");
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static RequestPostProcessor admin() {
        return user(new AppUserDetails(1L, "system", "SYSTEM", "ADMIN"));
    }

    private static RequestPostProcessor investigator() {
        return user(new AppUserDetails(1L, "system", "SYSTEM", "INVESTIGATOR"));
    }

    private long scoredAlert(LocalDate batchDate, String score) {
        long n = SEQ.incrementAndGet();
        Long ruleId = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values (?, 1, '테스트 룰', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_API_" + n);
        Long scanRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, batchDate);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date, score_status)
                values (?, ?, ?, ?, 'E1', '2022-09-01 00:01', '2022-09-02 00:00', '2022-09-02 00:00', '{}'::jsonb, ?,
                        'SCORED')
                returning id""", Long.class, "ALT-API-" + n, scanRunId, ruleId, "10_API" + n, batchDate);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"data_quality_status": "OK"}'::jsonb)""",
                alertId, score, "p-" + alertId);
        return alertId;
    }
}
