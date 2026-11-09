package com.team.aml.cases.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V006 계약 검증 (SCRUM-48) + V010 case_history 담당자 칸. db/migration 의 실제 마이그레이션 전체(V008 의 cases.current_draft_id FK 포함)를
 * app_migrate 로 적용하고,
 * 앱 계정(app_backend)으로 넣고 바꿔 보며 CHECK·UNIQUE·FK·권한을 SQLSTATE 로 확인한다.
 */
@Testcontainers
class CaseMigrationDbTest {

    static final String HASH = "a".repeat(64);
    static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    static Flyway flyway;
    static long batchRunId;
    static long ruleId;
    static long investigator;

    @BeforeAll
    static void migrate() throws Exception {
        flyway = Flyway.configure().dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops").defaultSchema("ops").locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();

        try (var c = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate")) {
            investigator = number(c, "insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                    + "values ('inv1','조사역1','INVESTIGATOR',1,1) returning id");
            batchRunId = number(c, "insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                    + "values ('RULE_SCAN','2022-09-01','SUCCEEDED',1) returning id");
            ruleId = number(c, "insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                    + "values ('R02_FAN_IN_24H',1,'fan-in','{}',1,1) returning id");
        }
    }

    // 1 정상
    @Test
    void 테이블_3개와_case_no_seq가_생기고_다시_적용하지_않는다() throws Exception {
        try (var c = backend()) {
            assertThat(number(c, "select count(*) from information_schema.tables where table_schema='ops' "
                    + "and table_name in ('cases','case_history','case_summaries')")).isEqualTo(3);
            long first = number(c, "select nextval('ops.case_no_seq')");
            assertThat(number(c, "select nextval('ops.case_no_seq')")).isEqualTo(first + 1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    // 2 정상 — 생성부터 종결까지 이력이 같이 쌓인다
    @Test
    void 케이스_생성_배정_메모_종결과_이력을_저장한다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            exec(c, history(caseId, "'OPEN'", "null", "'OPEN'", "null", "null", 0));
            exec(c, "update ops.cases set status='REVIEWING', assignee_id=" + investigator + ", version=1 where id=" + caseId);
            exec(c, assigneeHistory(caseId, "'ASSIGN'", "'OPEN'", "'REVIEWING'", "null", String.valueOf(investigator), 0));
            exec(c, history(caseId, "'NOTE'", "'REVIEWING'", "'REVIEWING'", "null", "''", 1));        // 빈 메모 = 비움 기록
            exec(c, "update ops.cases set status='CLOSED', close_reason='NO_ACTION', version=2 where id=" + caseId);
            exec(c, history(caseId, "'CLOSE'", "'REVIEWING'", "'CLOSED'", "'NO_ACTION'", "'확인 결과 조치 불필요'", 2));

            assertThat(number(c, "select count(*) from ops.case_history where case_id=" + caseId)).isEqualTo(4);
        }
    }

    // 3 실패 — 경보당 케이스 하나, 없는 경보, 번호 모양
    @Test
    void 같은_경보에_케이스를_두_번_만들_수_없다() throws Exception {
        try (var c = backend()) {
            long alertId = newAlert(c);
            newCase(c, alertId);
            rejected(c, "23505", insertCase(alertId, nextCaseNo()));
            rejected(c, "23503", insertCase(-1, nextCaseNo()));
            rejected(c, "23514", insertCase(newAlert(c), "CASE-22-1"));
        }
    }

    // 4 경계 — 상태와 같이 움직여야 하는 칸
    @Test
    void 상태와_담당자_반려코드_종결사유_초안이_맞아야_한다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            long draftId = newDraft(c, caseId);                                                                // V008 FK: 진짜 초안
            rejected(c, "23514", "update ops.cases set status='UNKNOWN' where id=" + caseId);
            rejected(c, "23514", "update ops.cases set assignee_id=" + investigator + " where id=" + caseId);   // OPEN 인데 담당자
            rejected(c, "23514", "update ops.cases set status='REVIEWING' where id=" + caseId);                 // 담당자 없이 조사
            rejected(c, "23514", "update ops.cases set current_draft_id=" + draftId + " where id=" + caseId);     // 상신 전 초안

            exec(c, "update ops.cases set status='ESCALATED', assignee_id=" + investigator + " where id=" + caseId);
            rejected(c, "23514", "update ops.cases set status='DRAFTED' where id=" + caseId);                   // 초안 없이 DRAFTED
            exec(c, "update ops.cases set status='DRAFTED', current_draft_id=" + draftId + " where id=" + caseId);
            exec(c, "update ops.cases set status='PENDING_APPROVAL' where id=" + caseId);
            rejected(c, "23514", "update ops.cases set status='REJECTED' where id=" + caseId);                  // 반려 코드 없음
            rejected(c, "23514", "update ops.cases set rejection_reason_code='NEED_REVISION' where id=" + caseId); // REJECTED 아닌데 코드
            exec(c, "update ops.cases set status='REJECTED', rejection_reason_code='NOT_REPORTABLE' where id=" + caseId);
            rejected(c, "23514", "update ops.cases set status='CLOSED' where id=" + caseId);                    // 종결 사유·코드 정리 안 함
            rejected(c, "23514", "update ops.cases set status='CLOSED', rejection_reason_code=null, close_reason='OTHER' where id=" + caseId);
            exec(c, "update ops.cases set status='CLOSED', rejection_reason_code=null, close_reason='FALSE_POSITIVE' where id=" + caseId);
            exec(c, "update ops.cases set status='REPORTED', close_reason=null where id=" + caseId);           // REPORTED 값 허용
            rejected(c, "23514", "update ops.cases set version=-1 where id=" + caseId);
        }
    }

    // 5 실패 — 이력 행 모양
    @Test
    void 이력은_사유코드와_이전상태와_메모_규칙을_지킨다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            rejected(c, "23514", history(caseId, "'OPEN'", "'OPEN'", "'OPEN'", "null", "null", 0));            // 생성 행에 이전 상태
            rejected(c, "23514", history(caseId, "'ASSIGN'", "null", "'REVIEWING'", "null", "null", 0));       // 생성 아닌데 이전 상태 없음
            rejected(c, "23514", history(caseId, "'REJECT'", "'PENDING_APPROVAL'", "'REJECTED'", "null", "'x'", 0));        // 반려 코드 없음 (NULL 통과 방지)
            rejected(c, "23514", history(caseId, "'REJECT'", "'PENDING_APPROVAL'", "'REJECTED'", "'NO_ACTION'", "'x'", 0)); // 종결 사유를 반려 코드로
            rejected(c, "23514", history(caseId, "'CLOSE'", "'REVIEWING'", "'CLOSED'", "null", "'x'", 0));                  // 종결 사유 없음
            rejected(c, "23514", history(caseId, "'ESCALATE'", "'REVIEWING'", "'ESCALATED'", "'DUPLICATE'", "'x'", 0));     // 사유 코드 자리가 아님
            rejected(c, "23514", history(caseId, "'NOTE'", "'REVIEWING'", "'REVIEWING'", "null", "null", 0));               // 메모 전문 없음
            rejected(c, "23514", history(caseId, "'NOTE'", "'REVIEWING'", "'REVIEWING'", "null", "repeat('가', 10001)", 0)); // 10,000자 초과
            rejected(c, "23514", history(caseId, "'REPORTD'", "'APPROVED'", "'REPORTED'", "null", "null", 0));               // 없는 action
            exec(c, history(caseId, "'REPORT'", "'APPROVED'", "'REPORTED'", "null", "null", 2));                             // 모의 제출 (V010)

            exec(c, history(caseId, "'NOTE'", "'REVIEWING'", "'REVIEWING'", "null", "repeat('가', 10000)", 3));
            rejected(c, "23505", history(caseId, "'NOTE'", "'REVIEWING'", "'REVIEWING'", "null", "'두 번째'", 3));          // 같은 version 메모 두 번
            exec(c, history(caseId, "'REJECT'", "'PENDING_APPROVAL'", "'REJECTED'", "'NEED_REVISION'", "'보완'", 3));       // 같은 version 다른 행동은 된다
        }
    }

    // 5-1 경계 — 넘긴·받은 담당자 칸 (V010, 미결 32)
    @Test
    void 배정은_받은_사람만_재배정은_넘긴_사람과_받은_사람을_남긴다() throws Exception {
        long inv2;
        try (var m = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate")) {
            inv2 = number(m, "insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                    + "values ('inv-v010-" + SEQ.incrementAndGet() + "','조사역2','INVESTIGATOR',1,1) returning id");
        }
        String a = String.valueOf(investigator), b = String.valueOf(inv2);
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            exec(c, assigneeHistory(caseId, "'ASSIGN'", "'OPEN'", "'REVIEWING'", "null", a, 0));
            exec(c, assigneeHistory(caseId, "'REASSIGN'", "'REVIEWING'", "'REVIEWING'", a, b, 1));

            rejected(c, "23514", assigneeHistory(caseId, "'ASSIGN'", "'OPEN'", "'REVIEWING'", "null", "null", 0));   // 받은 사람 없음
            rejected(c, "23514", assigneeHistory(caseId, "'ASSIGN'", "'OPEN'", "'REVIEWING'", b, a, 0));        // 배정에 넘긴 사람
            rejected(c, "23514", assigneeHistory(caseId, "'REASSIGN'", "'REVIEWING'", "'REVIEWING'", "null", b, 1)); // 넘긴 사람 없음
            rejected(c, "23514", assigneeHistory(caseId, "'REASSIGN'", "'REVIEWING'", "'REVIEWING'", a, a, 1));     // 같은 사람
            rejected(c, "23514", assigneeHistory(caseId, "'ESCALATE'", "'REVIEWING'", "'ESCALATED'", "null", a, 2)); // 배정 아닌데 담당자 칸
            rejected(c, "23503", assigneeHistory(caseId, "'ASSIGN'", "'OPEN'", "'REVIEWING'", "null", "-1", 0));    // 없는 사용자

            assertThat(number(c, "select count(*) from ops.case_history where case_id=" + caseId
                    + " and to_assignee_id=" + investigator)).isEqualTo(1);
        }
        try (var m = DriverManager.getConnection(db.getJdbcUrl(), "app_migrate", "migrate")) {
            // 예전 이력(받은 사람 칸 빈 ASSIGN) 때문에 필수 CHECK 는 NOT VALID, 빈칸 CHECK 는 검사 완료
            assertThat(number(m, "select count(*) from pg_constraint where conname='case_history_assignee_required_ck' "
                    + "and not convalidated")).isEqualTo(1);
            assertThat(number(m, "select count(*) from pg_constraint where conname='case_history_assignee_only_ck' "
                    + "and convalidated")).isEqualTo(1);
        }
    }

    // 6 경계 — AI 요약은 입력 스냅샷·해시와 함께
    @Test
    void AI_요약은_입력_스냅샷과_해시를_함께_저장한다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            exec(c, summary(caseId, "'{\"summary_lines\":[]}'", "'{\"case_no\":\"x\"}'", "'" + HASH + "'"));
            exec(c, summary(caseId, "'{\"summary_lines\":[]}'", "'{\"case_no\":\"x\"}'", "'" + HASH + "'")); // 같은 입력 재생성도 새 행

            rejected(c, "23514", summary(caseId, "'{}'", "'{}'", "'" + HASH.toUpperCase() + "'"));
            rejected(c, "23514", summary(caseId, "'{}'", "'{}'", "'abc'"));
            rejected(c, "23514", summary(caseId, "'[]'", "'{}'", "'" + HASH + "'"));
            rejected(c, "23514", summary(caseId, "'{}'", "'[]'", "'" + HASH + "'"));
            rejected(c, "23502", summary(caseId, "'{}'", "null", "'" + HASH + "'"));
        }
    }

    // 7 실패 — app_backend 권한
    @Test
    void 앱_계정은_케이스를_지울_수_없고_이력과_요약은_추가만_된다() throws Exception {
        try (var c = backend()) {
            long caseId = newCase(c, newAlert(c));
            exec(c, history(caseId, "'OPEN'", "null", "'OPEN'", "null", "null", 0));
            exec(c, summary(caseId, "'{}'", "'{}'", "'" + HASH + "'"));

            rejected(c, "42501", "delete from ops.cases where id=" + caseId);
            rejected(c, "42501", "update ops.case_history set comment='x' where case_id=" + caseId);
            rejected(c, "42501", "delete from ops.case_history where case_id=" + caseId);
            rejected(c, "42501", "update ops.case_summaries set payload='{}' where case_id=" + caseId);
            rejected(c, "42501", "delete from ops.case_summaries where case_id=" + caseId);
            exec(c, "update ops.cases set version=version+1 where id=" + caseId);                           // 케이스 수정은 된다
        }
    }

    // ---- 도우미 ----

    private static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
    }

    private static long newAlert(Connection c) throws SQLException {
        int n = SEQ.incrementAndGet();
        return number(c, "insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end, "
                + "reference_ts, rule_facts, batch_date, score_status) values ('ALT-20220901-" + String.format("%06d", n) + "',"
                + batchRunId + "," + ruleId + ",'ACC-" + n + "','2022-09-01 00:00','2022-09-02 00:00','2022-09-02 00:00',"
                + "'{}','2022-09-01','SCORED') returning id");
    }

    private static long newCase(Connection c, long alertId) throws SQLException {
        return number(c, insertCase(alertId, nextCaseNo()) + " returning id");
    }

    private static String nextCaseNo() {
        return "CASE-2022-" + String.format("%06d", SEQ.incrementAndGet());
    }

    private static String insertCase(long alertId, String caseNo) {
        return "insert into ops.cases(case_no, alert_id, account_key, status, created_by, updated_by) values ('"
                + caseNo + "'," + alertId + ",'ACC-" + alertId + "','OPEN',1,1)";
    }

    /** 초안 요청 기록 → SUCCEEDED → 초안 (V008 의 str_drafts → str_draft_requests 복합 FK 순서). */
    private static long newDraft(Connection c, long caseId) throws SQLException {
        long draftId = number(c, "select nextval('ops.str_draft_id_seq')");
        exec(c, "insert into ops.str_draft_requests(draft_id, case_id, request_snapshot, created_by, updated_by) values ("
                + draftId + "," + caseId + ",'{}',1,1)");
        exec(c, "update ops.str_draft_requests set status='SUCCEEDED', finished_at=now() where draft_id=" + draftId);
        exec(c, "insert into ops.str_drafts(id, case_id, generation_id, generator_version, produced_at, payload, created_by, updated_by) "
                + "values (" + draftId + "," + caseId + ",'g-" + draftId + "','test','2026-11-03T01:10:00Z',"
                + "'{\"facts\":{\"tx_count\":12}}',1,1)");
        return draftId;
    }

    private static String history(long caseId, String action, String from, String to,
                                  String reasonCode, String comment, long versionBefore) {
        return "insert into ops.case_history(case_id, action, from_status, to_status, reason_code, comment, "
                + "case_version_before, created_by) values (" + caseId + "," + action + "," + from + "," + to + ","
                + reasonCode + "," + comment + "," + versionBefore + ",1)";
    }

    private static String assigneeHistory(long caseId, String action, String from, String to,
                                          String fromAssignee, String toAssignee, long versionBefore) {
        return "insert into ops.case_history(case_id, action, from_status, to_status, case_version_before, created_by, "
                + "from_assignee_id, to_assignee_id) values (" + caseId + "," + action + "," + from + "," + to + ","
                + versionBefore + ",1," + fromAssignee + "," + toAssignee + ")";
    }

    private static String summary(long caseId, String payload, String snapshot, String hash) {
        return "insert into ops.case_summaries(case_id, payload, input_snapshot, input_sha256, generator_version, "
                + "produced_at, based_on_case_version, created_by) values (" + caseId + "," + payload + "," + snapshot
                + "," + hash + ",'summary-test','2026-11-03T01:00:00Z',0," + investigator + ")";
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.execute(sql); }
    }

    private static long number(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    private static void rejected(Connection c, String sqlState, String sql) {
        assertThatThrownBy(() -> exec(c, sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(sqlState));
    }
}
