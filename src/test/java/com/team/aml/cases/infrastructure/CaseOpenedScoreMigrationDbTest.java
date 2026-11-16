package com.team.aml.cases.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V013 cases 생성 당시 점수 스냅샷 (SCRUM-236·237). V012 까지 적용한 DB 에 점수 있는·없는 케이스를 넣고 V013 을 적용해
 * ① 이미 있던 케이스의 점수 채우기(정책 칸은 null) ② 칸 묶음 CHECK 를 SQLSTATE 로 본다.
 */
@Testcontainers
class CaseOpenedScoreMigrationDbTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    static long scoredCase;
    static long scoreId;
    static long unscoredCase;
    static long batchRunId;
    static long ruleId;
    static int seq;

    @BeforeAll
    static void migrateAroundV013() throws Exception {
        flyway(MigrationVersion.fromVersion("12")).migrate();
        try (var c = migrateUser()) {
            batchRunId = number(c, "insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                    + "values ('RULE_SCAN','2022-09-01','SUCCEEDED',1) returning id");
            ruleId = number(c, "insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                    + "values ('R02_FAN_IN_24H',1,'fan-in','{}',1,1) returning id");
            long scoredAlert = alert(c);
            scoreId = number(c, "insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version, "
                    + "prediction_id, produced_at, payload) values (" + scoredAlert + ", 0.93, 0.7, 'xgb-old', 'f-old', "
                    + "'p-1', now(), '{}') returning id");
            scoredCase = newCase(c, scoredAlert);
            unscoredCase = newCase(c, alert(c));   // 점수 행이 없는 경보 (정상 흐름엔 없지만 채우기가 건너뛰는지 본다)
        }
        Flyway latest = flyway(MigrationVersion.LATEST);
        latest.migrate();
        latest.validate();
    }

    @Test
    void 이미_있던_케이스는_경보_점수로_채우고_정책_칸은_비운다() throws Exception {
        try (var c = migrateUser(); var s = c.createStatement(); var r = s.executeQuery("""
                select opened_score_id, opened_score, opened_model_version, opened_feature_version,
                       opened_policy_version, opened_low_to_medium, opened_medium_to_high
                from ops.cases where id = """ + scoredCase)) {
            r.next();
            assertThat(r.getLong("opened_score_id")).isEqualTo(scoreId);
            assertThat(r.getBigDecimal("opened_score")).isEqualByComparingTo(new BigDecimal("0.93"));
            assertThat(r.getString("opened_model_version")).isEqualTo("xgb-old");
            assertThat(r.getString("opened_feature_version")).isEqualTo("f-old");
            assertThat(r.getString("opened_policy_version")).isNull();
            assertThat(r.getObject("opened_low_to_medium")).isNull();
            assertThat(r.getObject("opened_medium_to_high")).isNull();
        }
        try (var c = migrateUser()) {
            assertThat(number(c, "select count(*) from ops.cases where id = " + unscoredCase
                    + " and opened_score is null and opened_score_id is null")).isEqualTo(1);
        }
    }

    @Test
    void 점수_네_칸과_정책_세_칸은_함께_있거나_함께_없다() throws Exception {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend")) {
            long id = newCase(c, alert(c));
            String all = "opened_score_id=1, opened_score=0.95, opened_model_version='m', opened_feature_version='f'";
            rejected(c, "update ops.cases set opened_score=0.95 where id=" + id);                     // 점수만
            rejected(c, "update ops.cases set " + all.replace("0.95", "1.2") + " where id=" + id);   // 0~1 밖
            rejected(c, "update ops.cases set opened_policy_version='v1', opened_low_to_medium=0.5, "
                    + "opened_medium_to_high=0.9 where id=" + id);                                    // 점수 없이 정책
            exec(c, "update ops.cases set " + all + " where id=" + id);                              // 점수만 (예전 케이스 모양)
            rejected(c, "update ops.cases set opened_policy_version='v1' where id=" + id);           // 정책 한 칸만
            exec(c, "update ops.cases set opened_policy_version='v1', opened_low_to_medium=0.5, "
                    + "opened_medium_to_high=0.9 where id=" + id);
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops").defaultSchema("ops").locations("classpath:db/migration").target(target).load();
    }

    private static Connection migrateUser() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate");
    }

    private static long alert(Connection c) throws SQLException {
        int n = ++seq;
        return number(c, "insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end, "
                + "reference_ts, rule_facts, batch_date, score_status) values ('ALT-20220901-" + String.format("%06d", n) + "',"
                + batchRunId + "," + ruleId + ",'ACC-" + n + "','2022-09-01 00:00','2022-09-02 00:00','2022-09-02 00:00',"
                + "'{}','2022-09-01','SCORED') returning id");
    }

    private static long newCase(Connection c, long alertId) throws SQLException {
        return number(c, "insert into ops.cases(case_no, alert_id, account_key, status, created_by, updated_by) values ('"
                + "CASE-2022-" + String.format("%06d", ++seq) + "'," + alertId + ",'ACC-" + alertId + "','OPEN',1,1) returning id");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.execute(sql); }
    }

    private static long number(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    private static void rejected(Connection c, String sql) {
        assertThatThrownBy(() -> exec(c, sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
}
