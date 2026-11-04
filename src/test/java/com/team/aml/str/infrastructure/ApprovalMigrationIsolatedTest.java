package com.team.aml.str.infrastructure;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V009(approvals) 계약 검증. V008보다 낮은 번호 파일(V001 ~ V006) → V008 → V009 순서로 적용한다.
 * 케이스는 실제 V006 위에 만든다(ESCALATED + 담당자, StrMigrationIsolatedTest와 같은 재료).
 * 파일 목록 도우미는 StrMigrationIsolatedTest 것을 같이 쓴다.
 */
@Testcontainers
class ApprovalMigrationIsolatedTest {

    @TempDir static Path migrations;
    static Flyway flyway;
    static final String HASH = "a".repeat(64);
    static final String OTHER_HASH = "b".repeat(64);
    static final AtomicInteger SEQ = new AtomicInteger();
    static long batchRunId;
    static long ruleId;
    static long investigator;

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
        List<Path> earlier = StrMigrationIsolatedTest.migrationsBefore(8.0);   // V001 ~ V006 실제 파일
        for (Path f : earlier) Files.copy(f, migrations.resolve(f.getFileName()));
        flyway = Flyway.configure().dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops").defaultSchema("ops")
                .locations("filesystem:" + migrations).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(earlier.size());

        try (var c = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate")) {
            // 케이스를 만들 재료: 조사역·배치·룰 (CaseMigrationDbTest와 같은 값)
            investigator = number(c, "insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                    + "values ('inv1','조사역1','INVESTIGATOR',1,1) returning id");
            batchRunId = number(c, "insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                    + "values ('RULE_SCAN','2022-09-01','SUCCEEDED',1) returning id");
            ruleId = number(c, "insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                    + "values ('R02_FAN_IN_24H',1,'fan-in','{}',1,1) returning id");
        }
        for (String name : List.of("V008__create_str_tables.sql", "V009__create_approvals.sql")) {
            Files.copy(StrMigrationIsolatedTest.migrationFile(name), migrations.resolve(name));
        }
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        flyway.validate();
    }

    // 1
    @Test
    void 승인은_검증이_있어야_하고_반려_코드는_없다() throws Exception {
        try (var c = backend()) {
            Draft d = newDraft(c);
            rejected(c, "23514", approveSql(d, null));                                                   // 검증 없음
            rejected(c, "23514", insertSql(d, d.verificationId(), "APPROVED", "'NEED_REVISION'", HASH, null)); // 승인에 반려 코드
            exec(c, approveSql(d, d.verificationId()));
        }
    }

    // 2
    @Test
    void 반려는_반려_코드와_공백_아닌_사유가_있어야_한다() throws Exception {
        try (var c = backend()) {
            Draft d = newDraft(c);
            rejected(c, "23514", insertSql(d, null, "REJECTED", null, HASH, "'사유'"));                   // 코드 없음
            rejected(c, "23514", insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, null));          // 사유 없음
            rejected(c, "23514", insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "'   '"));       // 공백만
            rejected(c, "23514", insertSql(d, null, "REJECTED", "'OTHER'", HASH, "'사유'"));              // 없는 코드
            rejected(c, "23514", insertSql(d, null, "REPORTED", null, HASH, null));                       // 없는 결정
            rejected(c, "23514", insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "repeat('가', 2001)"));
            exec(c, insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "repeat('가', 2000)"));       // 경계: 2,000자
            exec(c, insertSql(d, null, "REJECTED", "'NOT_REPORTABLE'", HASH, "'보고 안 함'"));
        }
    }

    // 3
    @Test
    void 본문_해시는_필수이고_소문자_16진수_64자다() throws Exception {
        try (var c = backend()) {
            Draft d = newDraft(c);
            rejected(c, "23502", insertSql(d, d.verificationId(), "APPROVED", null, null, null));
            rejected(c, "23514", insertSql(d, d.verificationId(), "APPROVED", null, HASH.toUpperCase(), null));
        }
    }

    // 4
    @Test
    void 그_초안의_수정본과_검증으로만_기록한다() throws Exception {
        try (var c = backend()) {
            Draft a = newDraft(c);
            Draft b = newDraft(c);
            rejected(c, "23503", approveSql(new Draft(-1, a.revisionId(), a.verificationId()), a.verificationId())); // 없는 초안
            rejected(c, "23503", approveSql(new Draft(a.draftId(), b.revisionId(), a.verificationId()), a.verificationId())); // 남의 수정본
            rejected(c, "23503", approveSql(a, b.verificationId()));                                         // 남의 검증
        }
    }

    // 5
    @Test
    void 추가만_되고_마지막_반려를_찾을_수_있다() throws Exception {
        try (var c = backend()) {
            Draft d = newDraft(c);
            exec(c, insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "'처음 반려'"));
            exec(c, insertSql(d, null, "REJECTED", "'NEED_REVISION'", OTHER_HASH, "'두 번째 반려'"));

            rejected(c, "42501", "update ops.approvals set comment='x' where draft_id=" + d.draftId());
            rejected(c, "42501", "delete from ops.approvals where draft_id=" + d.draftId());
            // NarrativeUnchanged 비교에 쓰는 조회: 마지막 반려의 본문 해시
            assertThat(text(c, "select narrative_sha256 from ops.approvals where draft_id=" + d.draftId()
                    + " and decision='REJECTED' order by created_at desc, id desc limit 1")).isEqualTo(OTHER_HASH);
        }
    }

    // 6
    @Test
    void 승인은_초안당_한_번이고_반려는_여러_번_된다() throws Exception {
        try (var c = backend()) {
            Draft d = newDraft(c);
            exec(c, insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "'보완 요청'"));
            exec(c, insertSql(d, null, "REJECTED", "'NEED_REVISION'", HASH, "'한 번 더'"));          // 반려는 여러 번
            exec(c, approveSql(d, d.verificationId()));
            rejected(c, "23505", approveSql(d, d.verificationId()));                                 // 두 번째 승인

            Draft other = newDraft(c);
            exec(c, approveSql(other, other.verificationId()));                                      // 다른 초안은 따로 승인된다
        }
    }

    // ---- 도우미 ----

    record Draft(long draftId, long revisionId, long verificationId) {}

    /** 경보 → 케이스(ESCALATED + 담당자) → 요청(끝남) → 초안 → 수정본 1번 → 통과한 검증 한 줄. */
    private static Draft newDraft(Connection c) throws SQLException {
        long caseId = newCase(c);
        long draftId = number(c, "select nextval('ops.str_draft_id_seq')");
        exec(c, "insert into ops.str_draft_requests(draft_id, case_id, request_snapshot, status, finished_at, created_by, updated_by) "
                + "values (" + draftId + "," + caseId + ",'{}','SUCCEEDED',now(),1,1)");
        exec(c, "insert into ops.str_drafts(id, case_id, generation_id, generator_version, produced_at, payload, created_by, updated_by) "
                + "values (" + draftId + "," + caseId + ",'g-" + draftId + "','test','2026-11-03T01:10:00Z','{}',1,1)");
        long revisionId = number(c, "insert into ops.str_revisions(draft_id, revision_no, narrative_before, narrative_after, created_by) "
                + "values (" + draftId + ",1,'[]','[]',1) returning id");
        long verificationId = number(c, "insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) values ('v-" + draftId + "',"
                + draftId + ",'g-" + draftId + "',1,'" + HASH + "','PASSED','verify-test','2026-11-03T01:12:00Z','{}',1) returning id");
        return new Draft(draftId, revisionId, verificationId);
    }

    /** 경보 → 케이스. 초안을 붙이려면 ESCALATED + 담당자여야 V006 CHECK를 통과한다. */
    private static long newCase(Connection c) throws SQLException {
        int n = SEQ.incrementAndGet();
        long alertId = number(c, "insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, "
                + "window_end, reference_ts, rule_facts, batch_date, score_status) values ('ALT-20220901-"
                + String.format("%06d", n) + "'," + batchRunId + "," + ruleId + ",'ACC-" + n + "','2022-09-01 00:00',"
                + "'2022-09-02 00:00','2022-09-02 00:00','{}','2022-09-01','SCORED') returning id");
        return number(c, "insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, created_by, updated_by) "
                + "values ('CASE-2022-" + String.format("%06d", n) + "'," + alertId + ",'ACC-" + n + "','ESCALATED',"
                + investigator + ",1,1) returning id");
    }

    private static String approveSql(Draft d, Long verificationId) {
        return insertSql(d, verificationId, "APPROVED", null, HASH, null);
    }

    /** reasonCode·comment는 SQL 식 그대로('…', repeat(…), null). */
    private static String insertSql(Draft d, Long verificationId, String decision, String reasonCode, String hash, String comment) {
        return "insert into ops.approvals(draft_id, revision_id, verification_result_id, decision, reason_code, "
                + "narrative_sha256, comment, created_by) values (" + d.draftId() + "," + d.revisionId() + ","
                + verificationId + ",'" + decision + "'," + reasonCode + "," + (hash == null ? "null" : "'" + hash + "'") + ","
                + comment + ",1)";
    }

    private static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.execute(sql); }
    }

    private static long number(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }

    private static void rejected(Connection c, String sqlState, String sql) {
        assertThatThrownBy(() -> exec(c, sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(sqlState));
    }
}
