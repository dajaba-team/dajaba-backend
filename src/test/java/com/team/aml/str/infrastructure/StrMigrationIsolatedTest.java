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
 * V008 계약 검증. develop에 있는 V008보다 낮은 번호 파일(V001 ~ V006)을 전부 적용한 뒤 V008을 적용한다.
 * 케이스는 실제 V006 위에 만든다. 초안을 붙이려면 ESCALATED + 담당자여야 V006 CHECK를 통과한다(CaseMigrationDbTest와 같은 재료).
 */
@Testcontainers
class StrMigrationIsolatedTest {

    @TempDir static Path migrations;
    static Flyway flyway;
    static final String HASH = "a".repeat(64);
    static final String V008 = "V008__create_str_tables.sql";
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
        List<Path> earlier = migrationsBefore(8.0);                 // V001 ~ V006 실제 파일
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
        Files.copy(migrationFile(V008), migrations.resolve(V008));
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
    }

    // 1
    @Test
    void 테이블_4개와_draft_id_시퀀스가_생기고_다시_적용하지_않는다() throws Exception {
        try (var c = backend()) {
            assertThat(number(c, "select count(*) from information_schema.tables where table_schema='ops' and table_name in "
                    + "('str_draft_requests','str_drafts','str_revisions','verification_results')")).isEqualTo(4);
            long first = number(c, "select nextval('ops.str_draft_id_seq')");
            assertThat(number(c, "select nextval('ops.str_draft_id_seq')")).isEqualTo(first + 1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    // 2
    @Test
    void 케이스마다_진행_중인_요청은_하나이고_끝나면_finished_at이_있어야_한다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c);
            long draftId = newRequest(c, caseId);

            rejected(c, "23505", insertRequestSql("nextval('ops.str_draft_id_seq')", caseId));   // 진행 중 하나 더
            rejected(c, "23514", "update ops.str_draft_requests set status='SUCCEEDED' where draft_id=" + draftId); // 끝난 시각 없음
            rejected(c, "23514", "update ops.str_draft_requests set status='DONE', finished_at=now() where draft_id=" + draftId);

            exec(c, "update ops.str_draft_requests set status='SUCCEEDED', finished_at=now() where draft_id=" + draftId);
            newRequest(c, caseId);                                                            // 끝났으니 새 요청은 된다
        }
    }

    // 3
    @Test
    void 초안은_같은_케이스의_요청으로만_만들고_케이스마다_사용_중인_초안은_하나다() throws Exception {
        try (var c = backend()) {
            long caseA = newCase(c);
            long caseB = newCase(c);
            rejected(c, "23503", insertDraftSql(999_999, caseA));                            // 요청 기록 없는 번호

            long requestOfA = finishedRequest(c, caseA);
            rejected(c, "23503", insertDraftSql(requestOfA, caseB));                         // A의 요청으로 B의 초안
            exec(c, insertDraftSql(requestOfA, caseA));

            long second = finishedRequest(c, caseA);
            rejected(c, "23505", insertDraftSql(second, caseA));                             // ACTIVE 두 개
            exec(c, "update ops.str_drafts set status='SUPERSEDED' where id=" + requestOfA);
            exec(c, insertDraftSql(second, caseA));                                          // 대체 뒤에는 된다

            rejected(c, "23514", "update ops.str_drafts set status='UNKNOWN' where id=" + second);
            rejected(c, "23514", "update ops.str_drafts set suspicion_level_branch=0 where id=" + second);
            rejected(c, "23514", "update ops.str_drafts set suspicion_level_hq=6 where id=" + second);
            exec(c, "update ops.str_drafts set suspicion_types='{VI-4}', suspicion_level_branch=4 where id=" + second);
            rejected(c, "42501", "delete from ops.str_drafts where id=" + requestOfA);          // 초안은 지우지 않는다
            rejected(c, "42501", "delete from ops.str_draft_requests where draft_id=" + requestOfA);
        }
    }

    // 4
    @Test
    void facts는_payload에서_자동으로_나오고_직접_고칠_수_없다() throws Exception {
        try (var c = backend()) {
            long draftId = newDraft(c, newCase(c));

            assertThat(text(c, "select facts ->> 'tx_count' from ops.str_drafts where id=" + draftId)).isEqualTo("12");
            rejected(c, "428C9", "update ops.str_drafts set facts='{}' where id=" + draftId);   // 자동 계산 칸
        }
    }

    // 5
    @Test
    void 수정본은_추가만_되고_현재_수정본은_그_초안의_것이어야_한다() throws Exception {
        try (var c = backend()) {
            long draftA = newDraft(c, newCase(c));
            long draftB = newDraft(c, newCase(c));
            long revA = number(c, insertRevisionSql(draftA, 1) + " returning id");           // 1번 = AI 원문
            long revB = number(c, insertRevisionSql(draftB, 1) + " returning id");

            rejected(c, "23503", "update ops.str_drafts set current_revision_id=" + revB + " where id=" + draftA); // 남의 수정본
            exec(c, "update ops.str_drafts set current_revision_id=" + revA + " where id=" + draftA);

            rejected(c, "23505", insertRevisionSql(draftA, 1));                              // 같은 번호
            rejected(c, "23514", insertRevisionSql(draftA, 0));                              // 0번 없음
            rejected(c, "42501", "update ops.str_revisions set narrative_after='[]' where id=" + revA);
            rejected(c, "42501", "delete from ops.str_revisions where id=" + revA);
        }
    }

    // 6
    @Test
    void 검증_결과는_그_초안의_generation_id로만_저장되고_추가만_된다() throws Exception {
        try (var c = backend()) {
            long draftA = newDraft(c, newCase(c));
            long draftB = newDraft(c, newCase(c));

            rejected(c, "23503", insertVerificationSql("v-other", draftA, "g-" + draftB, HASH, "PASSED", "2026-11-03T01:12:00Z"));
            exec(c, insertVerificationSql("v-own", draftA, "g-" + draftA, HASH, "PASSED", "2026-11-03T01:12:00Z"));

            rejected(c, "42501", "update ops.verification_results set status='CODE_FAILED' where draft_id=" + draftA);
            rejected(c, "42501", "delete from ops.verification_results where draft_id=" + draftA);
        }
    }

    // 7
    @Test
    void 검증_결과는_해시_모양과_상태_값을_지키고_최신은_AI_검증_시각으로_정한다() throws Exception {
        try (var c = backend()) {
            long draftId = newDraft(c, newCase(c));
            String gen = "g-" + draftId;
            rejected(c, "23514", insertVerificationSql("v-upper", draftId, gen, HASH.toUpperCase(), "PASSED", "2026-11-03T01:12:00Z"));
            rejected(c, "23514", insertVerificationSql("v-status", draftId, gen, HASH, "OK", "2026-11-03T01:12:00Z"));

            exec(c, insertVerificationSql("v-new", draftId, gen, HASH, "PASSED", "2026-11-03T01:12:00Z"));
            rejected(c, "23505", insertVerificationSql("v-new", draftId, gen, HASH, "PASSED", "2026-11-03T01:12:00Z")); // 같은 verification_id
            exec(c, insertVerificationSql("v-old", draftId, gen, HASH, "LLM_FAILED", "2026-11-03T01:00:00Z"));          // 늦게 저장된 예전 결과

            assertThat(text(c, "select passed::text from ops.verification_results where verification_id='v-new'")).isEqualTo("true");
            assertThat(text(c, "select passed::text from ops.verification_results where verification_id='v-old'")).isEqualTo("false");
            assertThat(text(c, "select verification_id from ops.verification_results where draft_id=" + draftId
                    + " and narrative_sha256='" + HASH + "' order by produced_at desc, id desc limit 1")).isEqualTo("v-new");
        }
    }

    // 8
    @Test
    void cases의_current_draft_id는_같은_케이스의_초안만_가리킨다() throws Exception {
        try (var c = backend()) {
            long caseA = newCase(c);
            long caseB = newCase(c);
            long draftA = newDraft(c, caseA);
            long draftB = newDraft(c, caseB);

            rejected(c, "23503", "update ops.cases set current_draft_id=" + draftB + " where id=" + caseA);   // 남의 초안
            rejected(c, "23503", "update ops.cases set current_draft_id=-1 where id=" + caseA);               // 없는 초안
            exec(c, "update ops.cases set current_draft_id=" + draftA + " where id=" + caseA);
        }
    }

    // ---- 도우미 ----

    /** src/main/resources/db/migration 에서 버전이 limit 보다 낮은 .sql 파일 (V004_1 → 4.1). */
    static List<Path> migrationsBefore(double limit) throws Exception {
        Path dir = Path.of(StrMigrationIsolatedTest.class.getResource("/db/migration").toURI());
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("V\\d+(_\\d+)?__.*\\.sql"))
                    .filter(p -> version(p) < limit)
                    .sorted()
                    .toList();
        }
    }

    static double version(Path p) {
        String name = p.getFileName().toString();
        return Double.parseDouble(name.substring(1, name.indexOf("__")).replace('_', '.'));
    }

    static Path migrationFile(String name) throws Exception {
        var url = StrMigrationIsolatedTest.class.getResource("/db/migration/" + name);
        if (url == null) throw new IllegalStateException("Missing migration: " + name);
        return Path.of(url.toURI());
    }

    private static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
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

    private static long newRequest(Connection c, long caseId) throws SQLException {
        long draftId = number(c, "select nextval('ops.str_draft_id_seq')");
        exec(c, insertRequestSql(String.valueOf(draftId), caseId));
        return draftId;
    }

    private static long finishedRequest(Connection c, long caseId) throws SQLException {
        long draftId = newRequest(c, caseId);
        exec(c, "update ops.str_draft_requests set status='SUCCEEDED', finished_at=now() where draft_id=" + draftId);
        return draftId;
    }

    private static long newDraft(Connection c, long caseId) throws SQLException {
        long draftId = finishedRequest(c, caseId);
        exec(c, insertDraftSql(draftId, caseId));
        return draftId;
    }

    private static String insertRequestSql(String draftIdExpr, long caseId) {
        return "insert into ops.str_draft_requests(draft_id, case_id, request_snapshot, created_by, updated_by) values ("
                + draftIdExpr + "," + caseId + ",'{}',1,1)";
    }

    private static String insertDraftSql(long draftId, long caseId) {
        return "insert into ops.str_drafts(id, case_id, generation_id, generator_version, produced_at, payload, created_by, updated_by) "
                + "values (" + draftId + "," + caseId + ",'g-" + draftId + "','test','2026-11-03T01:10:00Z',"
                + "'{\"facts\":{\"tx_count\":12}}',1,1)";
    }

    private static String insertRevisionSql(long draftId, int revisionNo) {
        return "insert into ops.str_revisions(draft_id, revision_no, narrative_before, narrative_after, created_by) "
                + "values (" + draftId + "," + revisionNo + ",'[]','[]',1)";
    }

    private static String insertVerificationSql(String verificationId, long draftId, String generationId,
                                                String hash, String status, String producedAt) {
        return "insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) values ('"
                + verificationId + "'," + draftId + ",'" + generationId + "',1,'" + hash + "','" + status
                + "','verify-test','" + producedAt + "','{}',1)";
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