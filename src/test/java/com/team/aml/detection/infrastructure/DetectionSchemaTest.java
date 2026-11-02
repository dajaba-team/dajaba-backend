package com.team.aml.detection.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V005의 경보 중복 방지, 상태 제약, 계정 권한을 실제 PostgreSQL에서 확인한다. */
@Testcontainers
class DetectionSchemaTest {

    private static final String INSERT_ALERT = """
            insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                   reference_ts, rule_facts, batch_date)
            values (?, ?, ?, ?, ?::timestamp, ?::timestamp, ?::timestamp, '{}'::jsonb, '2022-09-02')
            returning id
            """;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops")
                .defaultSchema("ops")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void 같은_룰_버전_계좌_창_시작의_경보는_하나만_만든다() throws Exception {
        try (Connection c = backend()) {
            long ruleId = newRule(c, false);
            long batchId = newBatchRun(c);
            insertAlert(c, batchId, ruleId, "10_A1", "2022-09-02 00:00:00", "2022-09-03 00:00:00");

            assertSqlState("23505", () -> insertAlert(c, batchId, ruleId, "10_A1",
                    "2022-09-02 00:00:00", "2022-09-03 00:00:00"));
        }
    }

    @Test
    void 창_시작이_끝이나_기준_시각보다_늦으면_거부한다() throws Exception {
        try (Connection c = backend()) {
            long ruleId = newRule(c, false);
            long batchId = newBatchRun(c);

            assertSqlState("23514", () -> insertAlert(c, batchId, ruleId, "10_A2",
                    "2022-09-03 00:00:00", "2022-09-02 00:00:00"));
        }
    }

    @Test
    void 실패_코드는_FAILED_경보에만_정해진_값으로_넣는다() throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement()) {
            long alertId = newAlert(c);

            assertSqlState("23514", () -> statement.executeUpdate(
                    "update ops.alerts set score_failure_code = 'COMPUTE_ERROR' where id = " + alertId));
            assertSqlState("23514", () -> statement.executeUpdate(
                    "update ops.alerts set score_status = 'FAILED', score_failure_code = 'UNKNOWN' where id = " + alertId));

            statement.executeUpdate(
                    "update ops.alerts set score_status = 'FAILED', score_failure_code = 'COMPUTE_ERROR' where id = " + alertId);
        }
    }

    @Test
    void 룰_버전은_룰마다_한_번_활성_버전은_하나만_둔다() throws Exception {
        try (Connection c = backend()) {
            String ruleId = "R_TEST_" + SEQUENCE.incrementAndGet();
            insertRule(c, ruleId, 1, true);

            assertSqlState("23505", () -> insertRule(c, ruleId, 1, false));
            assertSqlState("23505", () -> insertRule(c, ruleId, 2, true));
            insertRule(c, ruleId, 2, false);
        }
    }

    @Test
    void 백엔드_계정은_경보의_상태_컬럼만_바꾸고_룰_경보는_지울_수_없다() throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement()) {
            long alertId = newAlert(c);

            statement.executeUpdate("update ops.alerts set score_status = 'SCORED', case_opened = true where id = " + alertId);
            assertSqlState("42501", () -> statement.executeUpdate(
                    "update ops.alerts set rule_facts = '{}'::jsonb where id = " + alertId));
            assertSqlState("42501", () -> statement.executeUpdate("delete from ops.alerts where id = " + alertId));
            assertSqlState("42501", () -> statement.executeUpdate("delete from ops.rules"));
            assertSqlState("42501", () -> statement.executeUpdate("update ops.alert_transactions set alert_id = alert_id"));
            assertSqlState("42501", () -> statement.executeUpdate("delete from ops.alert_transactions"));
        }
    }

    @Test
    void 경보_거래는_있는_거래만_연결한다() throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement()) {
            long alertId = newAlert(c);

            assertSqlState("23503", () -> statement.executeUpdate(
                    "insert into ops.alert_transactions(alert_id, transaction_id) values (" + alertId + ", 999999999)"));
        }
    }

    @Test
    void 백엔드_계정은_경보_번호_시퀀스를_쓸_수_있다() throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement();
             ResultSet result = statement.executeQuery("select nextval('ops.alert_no_seq')")) {
            result.next();
            assertThat(result.getLong(1)).isPositive();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "alerts", "alert_transactions"})
    void AI_계정은_탐지_테이블을_읽을_수_없다(String table) throws Exception {
        try (Connection c = connect("app_ai", "ai"); Statement statement = c.createStatement()) {
            assertSqlState("42501", () -> statement.executeQuery("select count(*) from ops." + table));
        }
    }

    private static Connection backend() throws SQLException {
        return connect("app_backend", "backend");
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), user, password);
    }

    private static long newAlert(Connection c) throws SQLException {
        return insertAlert(c, newBatchRun(c), newRule(c, false), "10_A" + SEQUENCE.incrementAndGet(),
                "2022-09-02 00:00:00", "2022-09-03 00:00:00");
    }

    private static long newRule(Connection c, boolean active) throws SQLException {
        return insertRule(c, "R_TEST_" + SEQUENCE.incrementAndGet(), 1, active);
    }

    private static long insertRule(Connection c, String ruleId, int version, boolean active) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement("""
                insert into ops.rules(rule_id, version, name, params, is_active, created_by, updated_by)
                values (?, ?, '테스트 룰', '{}'::jsonb, ?, 1, 1)
                returning id""")) {
            statement.setString(1, ruleId);
            statement.setInt(2, version);
            statement.setBoolean(3, active);
            return single(statement);
        }
    }

    private static long newBatchRun(Connection c) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2022-09-02', 'RUNNING', 1)
                returning id""")) {
            return single(statement);
        }
    }

    private static long insertAlert(Connection c, long batchId, long ruleId, String accountKey,
                                    String windowStart, String windowEnd) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(INSERT_ALERT)) {
            statement.setString(1, "ALT-TEST-" + SEQUENCE.incrementAndGet());
            statement.setLong(2, batchId);
            statement.setLong(3, ruleId);
            statement.setString(4, accountKey);
            statement.setString(5, windowStart);
            statement.setString(6, windowEnd);
            statement.setString(7, windowEnd);
            return single(statement);
        }
    }

    private static long single(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
