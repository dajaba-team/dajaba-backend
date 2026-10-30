package com.team.aml.cases.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V006 단독 계약 검증. V005가 아직 없으므로 alerts(id bigint)만 테스트 DB에 만든다.
 * 실제 V005와의 통합 검증을 대체하지 않는다. 전체 경로는 ApplicationContextTest로 검증한다.
 */
@Testcontainers
class CaseMigrationIsolatedTest {
    @TempDir static Path migrations;
    static Flyway flyway;

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @BeforeAll
    static void migrate() throws Exception {
        // 후속 마이그레이션과 분리해 V006 자체의 제약만 검증한다.
        for (String name : List.of("V001__create_schemas_and_roles.sql", "V002__create_source_tables.sql",
                "V003__create_users.sql", "V004__create_batch_runs.sql")) {
            copyMigration(name);
        }
        flyway = Flyway.configure().dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops").defaultSchema("ops")
                .locations("filesystem:" + migrations).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate");
             var statement = connection.createStatement()) {
            statement.execute("create table ops.alerts (id bigint generated always as identity primary key)");
        }
        copyMigration("V006__create_case_tables.sql");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
    }

    static void copyMigration(String name) throws Exception {
        try (var input = CaseMigrationIsolatedTest.class.getResourceAsStream("/db/migration/" + name)) {
            if (input == null) throw new IllegalStateException("Missing migration: " + name);
            Files.copy(input, migrations.resolve(name));
        }
    }

    @Test
    void createsThreeTablesAndNumberSequenceAndDoesNotReapply() throws Exception {
        try (var c = backend()) {
            assertThat(number(c, "select count(*) from information_schema.tables where table_schema='ops' "
                    + "and table_name in ('cases','case_history','case_summaries')")).isEqualTo(3);
            long first = number(c, "select nextval('ops.case_no_seq')");
            assertThat(number(c, "select nextval('ops.case_no_seq')")).isEqualTo(first + 1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void rejectsDuplicateAlertAndCaseNumberAndMissingReferences() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c);
            long alertId = number(c, "select alert_id from ops.cases where id=" + caseId);
            rejected(c, "23505", "insert into ops.cases(case_no,alert_id,created_by,updated_by) "
                    + "values ('duplicate-alert'," + alertId + ",1,1)");
            rejected(c, "23505", "insert into ops.cases(case_no,alert_id,created_by,updated_by) "
                    + "select case_no, (select id from ops.alerts where id not in "
                    + "(select alert_id from ops.cases) limit 1), 1, 1 from ops.cases where id=" + caseId);
            rejected(c, "23503", "update ops.cases set alert_id=-1 where id=" + caseId);
            rejected(c, "23503", "update ops.cases set assignee_id=-1 where id=" + caseId);
            rejected(c, "23503", "update ops.cases set updated_by=-1 where id=" + caseId);
            rejected(c, "23503", "insert into ops.case_history(case_id,action,to_status,created_by) "
                    + "values (-1,'OPEN','OPEN',1)");
        }
    }

    @Test
    void acceptsEightStatusesAndRejectsUnknownValuesAndNegativeVersion() throws Exception {
        try (var c = backend(); var statement = c.prepareStatement("update ops.cases set status=? where id=?")) {
            long id = newCase(c);
            for (String status : List.of("OPEN", "REVIEWING", "ESCALATED", "DRAFTED", "PENDING_APPROVAL",
                    "APPROVED", "REJECTED", "CLOSED")) {
                statement.setString(1, status);
                statement.setLong(2, id);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            for (String assignment : List.of("status='UNKNOWN'", "version=-1", "close_reason='UNKNOWN'",
                    "rejection_reason_code='UNKNOWN'")) {
                rejected(c, "23514", "update ops.cases set " + assignment + " where id=" + id);
            }
        }
    }

    @Test
    void storesFullNoteAndExplicitEmptyNoteButRejectsNullNote() throws Exception {
        try (var c = backend(); var insert = c.prepareStatement("""
                insert into ops.case_history(case_id,action,to_status,investigator_notes,created_by)
                values (?,'NOTE','OPEN',?,1) returning id
                """)) {
            long id = newCase(c);
            for (String note : List.of("조사 메모\n전체 내용".repeat(500), "")) {
                insert.setLong(1, id);
                insert.setString(2, note);
                try (var row = insert.executeQuery()) {
                    row.next();
                    try (var query = c.prepareStatement("select investigator_notes from ops.case_history where id=?")) {
                        query.setLong(1, row.getLong(1));
                        try (var saved = query.executeQuery()) {
                            saved.next();
                            assertThat(saved.getString(1)).isEqualTo(note);
                        }
                    }
                }
            }
            rejected(c, "23514", "insert into ops.case_history(case_id,action,to_status,created_by) "
                    + "values (" + id + ",'NOTE','OPEN',1)");
        }
    }

    @Test
    void retainsSummarySnapshotsAndAllowsRegenerationOfSameInput() throws Exception {
        try (var c = backend(); var insert = c.prepareStatement("""
                insert into ops.case_summaries(case_id,summary,generator_version,produced_at,
                    input_snapshot,input_sha256,payload,created_by)
                values (?,'요약','test-v1',now(),?::jsonb,?,'{}',1)
                """)) {
            long id = newCase(c);
            insert.setLong(1, id);
            insert.setString(2, "{\"investigator_notes\":\"원문\"}");
            insert.setString(3, "a".repeat(64));
            assertThat(insert.executeUpdate()).isEqualTo(1);
            assertThat(insert.executeUpdate()).isEqualTo(1);
            assertThat(number(c, "select count(*) from ops.case_summaries where case_id=" + id
                    + " and input_snapshot->>'investigator_notes'='원문'")).isEqualTo(2);
            insert.setString(3, "invalid-hash");
            assertThatThrownBy(insert::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("23514"));
            insert.setString(3, "a".repeat(64));
            insert.setString(2, "null");
            assertThatThrownBy(insert::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("23514"));
            insert.setString(2, null);
            assertThatThrownBy(insert::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("23502"));
        }
    }

    @Test
    void backendCannotRewriteHistoryAndAiCannotReadCaseData() throws Exception {
        try (var c = backend()) {
            for (String table : List.of("case_history", "case_summaries")) {
                rejected(c, "42501", "update ops." + table + " set created_at=now()");
                rejected(c, "42501", "delete from ops." + table);
            }
        }
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), "app_ai", "ai")) {
            for (String table : List.of("cases", "case_history", "case_summaries")) {
                rejected(c, "42501", "select * from ops." + table);
            }
        }
    }

    static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
    }

    static long newCase(Connection c) throws SQLException {
        // 중복 번호 검증에도 쓸 두 번째 경보를 준비한다.
        number(c, "insert into ops.alerts default values returning id");
        long alert = number(c, "insert into ops.alerts default values returning id");
        try (var statement = c.prepareStatement("insert into ops.cases(case_no,alert_id,created_by,updated_by) "
                + "values (?,?,1,1) returning id")) {
            statement.setString(1, "CASE-" + UUID.randomUUID().toString().substring(0, 20));
            statement.setLong(2, alert);
            try (var row = statement.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    static long number(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    static void rejected(Connection c, String state, String sql) throws SQLException {
        try (var statement = c.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo(state));
        }
    }
}
