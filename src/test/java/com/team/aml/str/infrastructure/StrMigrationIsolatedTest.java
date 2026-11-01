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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V008 단독 계약 검증. V005·V006이 아직 develop에 없어서 ops.cases는 V008이 참조하는 칸만 테스트 DB에 만든다.
 * V006이 develop에 들어오면 stub 대신 실제 V005·V006 파일을 복사하도록 바꾼다. 전체 경로는 ApplicationContextTest가 맡는다.
 */
@Testcontainers
class StrMigrationIsolatedTest {

    @TempDir static Path migrations;
    static Flyway flyway;
    static final String HASH = "a".repeat(64);

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
        for (String name : List.of("V001__create_schemas_and_roles.sql", "V002__create_source_tables.sql",
                "V003__create_users.sql", "V004__create_batch_runs.sql")) {
            copyMigration(name);
        }
        flyway = Flyway.configure().dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops").defaultSchema("ops")
                .locations("filesystem:" + migrations).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate");
             var s = c.createStatement()) {
            // V006 대신: V008이 참조하는 칸만
            s.execute("create table ops.cases (id bigint generated always as identity primary key, current_draft_id bigint)");
        }
        copyMigration("V008__create_str_tables.sql");
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

            rejected(c, "23505", "insert into ops.str_draft_requests(draft_id, case_id, request_snapshot, requested_by) "
                    + "values (nextval('ops.str_draft_id_seq')," + caseId + ",'{}',1)");          // 진행 중 하나 더
            rejected(c, "23514", "update ops.str_draft_requests set status='SUCCEEDED' where draft_id=" + draftId); // 끝난 시각 없음
            rejected(c, "23514", "update ops.str_draft_requests set status='DONE', finished_at=now() where draft_id=" + draftId);

            exec(c, "update ops.str_draft_requests set status='SUCCEEDED', finished_at=now() where draft_id=" + draftId);
            newRequest(c, caseId);                                                            // 끝났으니 새 요청은 된다
        }
    }

    // 3
    @Test
    void 초안은_요청_기록이_있어야_하고_케이스마다_사용_중인_초안은_하나다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c);
            rejected(c, "23503", insertDraftSql(999_999, caseId));                           // 요청 기록 없는 번호

            long first = newDraft(c, caseId);
            long second = newRequest(c, caseId);
            rejected(c, "23505", insertDraftSql(second, caseId));                            // ACTIVE 두 개

            exec(c, "update ops.str_drafts set status='SUPERSEDED' where id=" + first);
            exec(c, insertDraftSql(second, caseId));                                         // 대체 뒤에는 된다

            rejected(c, "23514", "update ops.str_drafts set status='UNKNOWN' where id=" + second);
            rejected(c, "23514", "update ops.str_drafts set suspicion_level_branch=0 where id=" + second);
            rejected(c, "23514", "update ops.str_drafts set suspicion_level_hq=6 where id=" + second);
            exec(c, "update ops.str_drafts set suspicion_types='{VI-4}', suspicion_level_branch=4 where id=" + second);
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
    void 수정본과_검증_결과는_추가만_된다() throws Exception {
        try (var c = backend()) {
            long draftId = newDraft(c, newCase(c));
            long rev1 = number(c, insertRevisionSql(draftId, 1) + " returning id");          // 1번 = AI 원문
            exec(c, "update ops.str_drafts set current_revision_id=" + rev1 + " where id=" + draftId);

            rejected(c, "23505", insertRevisionSql(draftId, 1));                             // 같은 번호
            rejected(c, "23514", insertRevisionSql(draftId, 0));                             // 0번 없음
            rejected(c, "42501", "update ops.str_revisions set narrative_after='[]' where id=" + rev1);
            rejected(c, "42501", "delete from ops.str_revisions where id=" + rev1);

            exec(c, insertVerificationSql("v-append", draftId, HASH, "PASSED"));
            rejected(c, "42501", "update ops.verification_results set status='CODE_FAILED' where draft_id=" + draftId);
            rejected(c, "42501", "delete from ops.verification_results where draft_id=" + draftId);
        }
    }

    // 6
    @Test
    void 검증_결과는_해시_모양과_상태_값을_지키고_passed는_status에서_나온다() throws Exception {
        try (var c = backend()) {
            long draftId = newDraft(c, newCase(c));
            rejected(c, "23514", insertVerificationSql("v-upper", draftId, HASH.toUpperCase(), "PASSED"));
            rejected(c, "23514", insertVerificationSql("v-status", draftId, HASH, "OK"));

            exec(c, insertVerificationSql("v-old", draftId, HASH, "PASSED"));
            rejected(c, "23505", insertVerificationSql("v-old", draftId, HASH, "PASSED"));   // 같은 verification_id
            exec(c, insertVerificationSql("v-new", draftId, HASH, "LLM_FAILED"));             // 같은 본문 재검증

            assertThat(text(c, "select passed::text from ops.verification_results where verification_id='v-old'")).isEqualTo("true");
            assertThat(text(c, "select passed::text from ops.verification_results where verification_id='v-new'")).isEqualTo("false");
            // 같은 초안·같은 해시는 최신이 먼저 (11/1 규칙에 쓰는 조회)
            assertThat(text(c, "select verification_id from ops.verification_results where draft_id=" + draftId
                    + " and narrative_sha256='" + HASH + "' order by created_at desc, id desc limit 1")).isEqualTo("v-new");
        }
    }

    // 7
    @Test
    void cases의_current_draft_id는_있는_초안만_가리킨다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c);
            long draftId = newDraft(c, caseId);
            exec(c, "update ops.cases set current_draft_id=" + draftId + " where id=" + caseId);
            rejected(c, "23503", "update ops.cases set current_draft_id=-1 where id=" + caseId);
        }
    }

    // ---- 도우미 ----

    static void copyMigration(String name) throws Exception {
        try (var input = StrMigrationIsolatedTest.class.getResourceAsStream("/db/migration/" + name)) {
            if (input == null) throw new IllegalStateException("Missing migration: " + name);
            Files.copy(input, migrations.resolve(name));
        }
    }

    private static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
    }

    private static long newCase(Connection c) throws SQLException {
        return number(c, "insert into ops.cases default values returning id");
    }

    private static long newRequest(Connection c, long caseId) throws SQLException {
        long draftId = number(c, "select nextval('ops.str_draft_id_seq')");
        exec(c, "insert into ops.str_draft_requests(draft_id, case_id, request_snapshot, requested_by) values ("
                + draftId + "," + caseId + ",'{}',1)");
        return draftId;
    }

    private static long newDraft(Connection c, long caseId) throws SQLException {
        long draftId = newRequest(c, caseId);
        exec(c, "update ops.str_draft_requests set status='SUCCEEDED', finished_at=now() where draft_id=" + draftId);
        exec(c, insertDraftSql(draftId, caseId));
        return draftId;
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

    private static String insertVerificationSql(String verificationId, long draftId, String hash, String status) {
        return "insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) values ('"
                + verificationId + "'," + draftId + ",'g-" + draftId + "',1,'" + hash + "','" + status
                + "','verify-test','2026-11-03T01:12:00Z','{}',1)";
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