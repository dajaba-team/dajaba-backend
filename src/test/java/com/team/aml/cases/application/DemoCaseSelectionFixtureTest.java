package com.team.aml.cases.application;

import com.team.aml.detection.application.BatchChain;
import com.team.aml.shared.batch.BatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Only run against the disposable localhost fixture database. */
@EnabledIfEnvironmentVariable(named = "DEMO_CASE_FIXTURE", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55472/demo_fixture",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:55472/demo_fixture",
        "aml.batch.chain-enabled=true",
        "aml.case-policy.version=fixture-case-v1", "aml.case-policy.low-to-medium=0.15",
        "aml.case-policy.medium-to-high=0.70"})
class DemoCaseSelectionFixtureTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BatchChain chain;

    @Test
    void createsExactlyTheSelectedAlertIdsEvenWhereTheRealScoreIsBelowHigh() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("demo_fixture");
        assertThat(jdbc.queryForObject("select count(*) from ops.alerts", Long.class)).isZero();
        var file = Path.of(System.getenv("AML_DEMO_CASE_SELECTION_FILE"));
        var lines = Files.readAllLines(file);
        var date = LocalDate.parse(lines.get(0));
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values ('R_FIXTURE', 1, 'Fixture', '{}'::jsonb, 1, 1) returning id
                """, Long.class);
        long scan = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'SUCCEEDED', 1) returning id
                """, Long.class, date);
        var expected = new ArrayList<Long>();
        for (String line : lines.subList(1, lines.size())) {
            var fields = line.split("\t");
            long id = Long.parseLong(fields[1]);
            seed(id, fields[0], new BigDecimal(fields[2]), ruleId, scan, date);
            expected.add(id);
        }
        seed(9999999L, "ALT-FIXTURE-UNSELECTED", new BigDecimal("0.99"), ruleId, scan, date);
        long scoreRun = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by, finished_at)
                values ('SCORE_REQUEST', ?, 'SUCCEEDED', 1, now()) returning id
                """, Long.class, date);
        chain.afterScore(scoreRun, BatchStatus.SUCCEEDED);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "select status from ops.batch_runs where batch_type='OPEN_CASES' and batch_date = ?",
                String.class, date)).containsExactly("SUCCEEDED"));
        assertThat(jdbc.queryForList("select alert_id from ops.cases", Long.class))
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(jdbc.queryForObject("select count(*) from ops.cases where opened_score < 0.7", Long.class))
                .isPositive();
        assertThat(jdbc.queryForObject("select count(*) from ops.alerts where case_opened", Long.class))
                .isEqualTo(expected.size());
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history", Long.class))
                .isEqualTo(expected.size());
    }

    private void seed(long id, String no, BigDecimal score, long rule, long scan, LocalDate date) {
        jdbc.update("""
                insert into ops.alerts(id, alert_no, batch_run_id, rule_ref_id, account_key, entity_id,
                  window_start, window_end, reference_ts, rule_facts, batch_date, score_status)
                overriding system value
                values (?, ?, ?, ?, ?, 'E_FIXTURE', ?, ?, ?, '{}'::jsonb, ?, 'SCORED')
                """, id, no, scan, rule, "10_FIXTURE" + id, date.minusDays(4).atTime(12, 0), date.atTime(12, 0),
                date.atTime(12, 0), date);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                  prediction_id, produced_at, payload)
                values (?, ?, 0.5, 'xgb-hi-medium-r04-event-c2-31-v1', 'hi-medium-evidence31-96h-r04-event-v1',
                  ?, now(), '{"data_quality_status":"OK"}'::jsonb)
                """, id, score, "fixture-" + id);
    }
}
