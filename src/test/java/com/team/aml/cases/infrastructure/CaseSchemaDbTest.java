package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V006 (SCRUM-48) — 실제 PostgreSQL 에서 Case·CaseHistory 매핑, 낙관적 락, CHECK·UNIQUE, app_backend 권한을 본다.
 * 경보는 V005 테이블에 SQL 로 직접 넣는다 (cases.alert_id FK 대상).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CaseSchemaDbTest {

    private static final long SYSTEM = 1L;
    private static final AtomicLong SEQ = new AtomicLong();

    @Container
    @ServiceConnection(type = JdbcConnectionDetails.class)
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    // Flyway 는 실제 DDL 소유자로 실행해야 revoke 를 검증할 수 있다 (AiCallLogDbTest 와 같음).
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    // ── 매핑 ──────────────────────────────────────────────────

    @Test
    void 정상_케이스와_OPEN_이력이_같이_저장된다() {
        long alertId = newAlert();
        String caseNo = caseNo();

        Long id = tx().execute(s -> save(Case.open(caseNo, alertId, "010_A001", null, SYSTEM)));

        Case loaded = cases.findByCaseNo(caseNo).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(loaded.getAccountKey()).isEqualTo("010_A001");
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getCreatedBy()).isEqualTo(SYSTEM);   // BaseEntity 감사 칸 (세션 없음 → SYSTEM)
        assertThat(loaded.getPendingHistories()).isEmpty();      // 이력은 엔티티가 들고 있지 않다

        List<CaseHistory> h = histories.findByCaseIdOrderByIdAsc(id);
        assertThat(h).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(CaseHistoryAction.OPEN);
            assertThat(row.getFromStatus()).isNull();
            assertThat(row.getToStatus()).isEqualTo(CaseStatus.OPEN);
            assertThat(row.getCaseVersionBefore()).isZero();
            assertThat(row.getCreatedBy()).isEqualTo(SYSTEM);
            assertThat(row.getCreatedAt()).isNotNull();
        });
        assertThat(cases.existsByAlertId(alertId)).isTrue();
    }

    @Test
    void 정상_변경하면_version이_오르고_이력에_직전_version이_남는다() {
        Long id = tx().execute(s -> save(Case.open(caseNo(), newAlert(), "010_A002", null, SYSTEM)));

        tx().executeWithoutResult(s -> {
            Case c = cases.findById(id).orElseThrow();
            c.assign(7L, 7L);
            save(c);
        });

        Case loaded = cases.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CaseStatus.REVIEWING);
        assertThat(loaded.getAssigneeId()).isEqualTo(7L);
        assertThat(loaded.getVersion()).isEqualTo(1L);
        assertThat(histories.findByCaseIdOrderByIdAsc(id))
                .extracting(CaseHistory::getAction, CaseHistory::getCaseVersionBefore, CaseHistory::getCreatedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CaseHistoryAction.OPEN, 0L, SYSTEM),
                        org.assertj.core.groups.Tuple.tuple(CaseHistoryAction.ASSIGN, 0L, 7L));
    }

    @Test
    void 실패_오래된_version으로_저장하면_낙관적_락에_걸린다() {
        Long id = tx().execute(s -> save(Case.open(caseNo(), newAlert(), "010_A003", null, SYSTEM)));
        Case stale = cases.findById(id).orElseThrow();   // version 0 을 들고 있다

        tx().executeWithoutResult(s -> {
            Case c = cases.findById(id).orElseThrow();
            c.assign(7L, 7L);
            save(c);
        });

        stale.assign(8L, 8L);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> cases.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(cases.findById(id).orElseThrow().getAssigneeId()).isEqualTo(7L);
    }

    // ── 제약 ──────────────────────────────────────────────────

    @Test
    void 실패_경보_하나에_케이스는_하나만() throws SQLException {
        long alertId = newAlert();
        try (Connection c = backend(); Statement st = c.createStatement()) {
            st.executeUpdate(insertCase(caseNo(), alertId, "'OPEN'", "null", "null", "null", "null"));
            assertSqlState("23505", () -> st.executeUpdate(
                    insertCase(caseNo(), alertId, "'OPEN'", "null", "null", "null", "null")));
        }
    }

    @Test
    void 실패_상태와_맞지_않는_담당자_초안_반려코드_종결사유는_거부된다() throws SQLException {
        try (Connection c = backend(); Statement st = c.createStatement()) {
            // 상태 · 담당자 · 초안 · 반려 코드 · 종결 사유
            assertCheck(st, "'OPEN'", "7", "null", "null", "null");                      // OPEN 인데 담당자
            assertCheck(st, "'REVIEWING'", "null", "null", "null", "null");              // 배정됐는데 담당자 없음
            assertCheck(st, "'REVIEWING'", "7", "300", "null", "null");                  // 상신 전인데 초안
            assertCheck(st, "'DRAFTED'", "7", "null", "null", "null");                   // DRAFTED 인데 초안 없음
            assertCheck(st, "'REJECTED'", "7", "300", "null", "null");                   // REJECTED 인데 반려 코드 없음
            assertCheck(st, "'DRAFTED'", "7", "300", "'NEED_REVISION'", "null");         // REJECTED 아닌데 반려 코드
            assertCheck(st, "'CLOSED'", "7", "null", "null", "null");                    // CLOSED 인데 종결 사유 없음
            assertCheck(st, "'REVIEWING'", "7", "null", "null", "'NO_ACTION'");          // CLOSED 아닌데 종결 사유
            assertCheck(st, "'UNKNOWN'", "7", "null", "null", "null");                   // 없는 상태
        }
    }

    @Test
    void 경계_9개_상태가_모두_저장된다() throws SQLException {
        try (Connection c = backend(); Statement st = c.createStatement()) {
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'OPEN'", "null", "null", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'REVIEWING'", "7", "null", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'ESCALATED'", "7", "null", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'DRAFTED'", "7", "300", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'PENDING_APPROVAL'", "7", "300", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'APPROVED'", "7", "300", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'REPORTED'", "7", "300", "null", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'REJECTED'", "7", "300", "'NOT_REPORTABLE'", "null"));
            st.executeUpdate(insertCase(caseNo(), newAlert(), "'CLOSED'", "7", "300", "null", "'DUPLICATE'"));
        }
    }

    @Test
    void 실패_케이스_번호_형식이_다르면_거부된다() throws SQLException {
        try (Connection c = backend(); Statement st = c.createStatement()) {
            assertSqlState("23514", () -> st.executeUpdate(
                    insertCase("CASE-22-000001", newAlert(), "'OPEN'", "null", "null", "null", "null")));
        }
    }

    @Test
    void 실패_이력의_사유_코드는_REJECT와_CLOSE에만_맞는_값으로() throws SQLException {
        long caseId = jdbc.queryForObject(
                insertCase(caseNo(), newAlert(), "'OPEN'", "null", "null", "null", "null") + " returning id", Long.class);
        try (Connection c = backend(); Statement st = c.createStatement()) {
            assertSqlState("23514", () -> st.executeUpdate(insertHistory(caseId, "'REJECT'", "'PENDING_APPROVAL'", "null", 1)));
            assertSqlState("23514", () -> st.executeUpdate(insertHistory(caseId, "'CLOSE'", "'REVIEWING'", "'NEED_REVISION'", 1)));
            assertSqlState("23514", () -> st.executeUpdate(insertHistory(caseId, "'ASSIGN'", "'OPEN'", "'NO_ACTION'", 1)));
            assertSqlState("23514", () -> st.executeUpdate(insertHistory(caseId, "'ASSIGN'", "null", "null", 1)));   // OPEN 아닌데 이전 상태 없음
            st.executeUpdate(insertHistory(caseId, "'REJECT'", "'PENDING_APPROVAL'", "'NEED_REVISION'", 1));
        }
    }

    @Test
    void 실패_같은_version의_메모는_한_번만() throws SQLException {
        long caseId = jdbc.queryForObject(
                insertCase(caseNo(), newAlert(), "'REVIEWING'", "7", "null", "null", "null") + " returning id", Long.class);
        try (Connection c = backend(); Statement st = c.createStatement()) {
            st.executeUpdate(insertNote(caseId, 3));
            assertSqlState("23505", () -> st.executeUpdate(insertNote(caseId, 3)));
            st.executeUpdate(insertNote(caseId, 4));
        }
    }

    // ── 권한 ──────────────────────────────────────────────────

    @Test
    void 실패_backend는_케이스를_지울_수_없고_이력과_요약은_추가만() throws SQLException {
        long caseId = jdbc.queryForObject(
                insertCase(caseNo(), newAlert(), "'OPEN'", "null", "null", "null", "null") + " returning id", Long.class);
        try (Connection c = backend(); Statement st = c.createStatement()) {
            assertThat(st.executeUpdate("update ops.cases set version = version + 1 where id = " + caseId)).isEqualTo(1);
            assertSqlState("42501", () -> st.executeUpdate("delete from ops.cases where id = " + caseId));

            st.executeUpdate(insertHistory(caseId, "'ASSIGN'", "'OPEN'", "null", 0));
            assertSqlState("42501", () -> st.executeUpdate("update ops.case_history set comment = 'x'"));
            assertSqlState("42501", () -> st.executeUpdate("delete from ops.case_history"));

            st.executeUpdate("""
                    insert into ops.case_summaries (case_id, payload, input_snapshot, input_sha256, model_version,
                                                    produced_at, based_on_case_version, created_by)
                    values (%d, '{}'::jsonb, '{}'::jsonb, repeat('a', 64), 'demo-model-v1', now(), 0, 1)
                    """.formatted(caseId));
            assertSqlState("42501", () -> st.executeUpdate("update ops.case_summaries set model_version = 'x'"));
            assertSqlState("42501", () -> st.executeUpdate("delete from ops.case_summaries"));
        }
    }

    @Test
    void 경계_backend는_케이스_번호_시퀀스를_쓸_수_있다() throws SQLException {
        try (Connection c = backend(); Statement st = c.createStatement();
             var rs = st.executeQuery("select nextval('ops.case_no_seq')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isPositive();
        }
    }

    // ── 도우미 ────────────────────────────────────────────────

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** application 이 할 일: 케이스 저장 → 대기 이력 꺼내 저장. */
    private Long save(Case c) {
        Case saved = cases.save(c);
        histories.saveAll(saved.takePendingHistories());
        return saved.getId();
    }

    private static String caseNo() {
        return "CASE-2022-%06d".formatted(SEQ.incrementAndGet());
    }

    /** V005 경보 한 행 (룰·배치 실행도 같이). id 를 돌려준다. */
    private long newAlert() {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2022-09-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_TEST_" + n);
        return jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2022-09-02 00:00:00', '2022-09-02 23:59:59', '2022-09-02 23:59:59',
                        '{}'::jsonb, '2022-09-02')
                returning id
                """, Long.class, "ALT-20220902-%06d".formatted(n), batchId, ruleId, "010_T" + n);
    }

    private static String insertCase(String caseNo, long alertId, String status, String assignee, String draft,
                                     String rejection, String closeReason) {
        return """
                insert into ops.cases (case_no, alert_id, account_key, status, assignee_id, current_draft_id,
                                       rejection_reason_code, close_reason, created_by, updated_by)
                values ('%s', %d, '010_A', %s, %s, %s, %s, %s, 1, 1)
                """.formatted(caseNo, alertId, status, assignee, draft, rejection, closeReason);
    }

    private static String insertHistory(long caseId, String action, String from, String reason, long versionBefore) {
        return """
                insert into ops.case_history (case_id, action, from_status, to_status, reason_code,
                                              case_version_before, created_by)
                values (%d, %s, %s, 'REVIEWING', %s, %d, 1)
                """.formatted(caseId, action, from, reason, versionBefore);
    }

    private static String insertNote(long caseId, long versionBefore) {
        return """
                insert into ops.case_history (case_id, action, from_status, to_status, comment,
                                              case_version_before, created_by)
                values (%d, 'NOTE', 'REVIEWING', 'REVIEWING', '메모', %d, 1)
                """.formatted(caseId, versionBefore);
    }

    private void assertCheck(Statement st, String status, String assignee, String draft,
                             String rejection, String closeReason) {
        assertSqlState("23514", () -> st.executeUpdate(
                insertCase(caseNo(), newAlert(), status, assignee, draft, rejection, closeReason)));
    }

    private static Connection backend() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
    }

    private static void assertSqlState(String state, SqlCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(state));
    }

    @FunctionalInterface
    private interface SqlCall {
        void run() throws SQLException;
    }
}
