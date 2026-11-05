package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Case·CaseHistory JPA 매핑 (SCRUM-98) — 실제 PostgreSQL 에서 저장·이력 분리·낙관적 락을 본다.
 * V006 의 CHECK·UNIQUE·권한은 CaseMigrationDbTest 가 본다(여기서 되풀이하지 않음).
 * 경보는 V005 테이블에 SQL 로 직접 넣는다 (cases.alert_id FK 대상).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CaseMappingDbTest {

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

    private long inv;      // 조사역 A (users FK 대상)
    private long inv2;     // 조사역 B
    private String INV;    // SQL 문자열용

    @BeforeEach
    void users() {
        inv = user("t_inv01");
        inv2 = user("t_inv02");
        INV = String.valueOf(inv);
    }

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
            c.assign(inv, inv);
            save(c);
        });

        Case loaded = cases.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CaseStatus.REVIEWING);
        assertThat(loaded.getAssigneeId()).isEqualTo(inv);
        assertThat(loaded.getVersion()).isEqualTo(1L);
        assertThat(histories.findByCaseIdOrderByIdAsc(id))
                .extracting(CaseHistory::getAction, CaseHistory::getCaseVersionBefore, CaseHistory::getCreatedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CaseHistoryAction.OPEN, 0L, SYSTEM),
                        org.assertj.core.groups.Tuple.tuple(CaseHistoryAction.ASSIGN, 0L, inv));
    }

    @Test
    void 실패_오래된_version으로_저장하면_낙관적_락에_걸린다() {
        Long id = tx().execute(s -> save(Case.open(caseNo(), newAlert(), "010_A003", null, SYSTEM)));
        Case stale = cases.findById(id).orElseThrow();   // version 0 을 들고 있다

        tx().executeWithoutResult(s -> {
            Case c = cases.findById(id).orElseThrow();
            c.assign(inv, inv);
            save(c);
        });

        stale.assign(inv2, inv2);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> cases.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(cases.findById(id).orElseThrow().getAssigneeId()).isEqualTo(inv);
    }

    // ── 제약 ──────────────────────────────────────────────────

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** application 이 할 일: 케이스 저장 → 대기 이력 꺼내 저장. */
    private Long save(Case c) {
        Case saved = cases.save(c);
        histories.saveAll(saved.takePendingHistories());
        return saved.getId();
    }

    /** 테스트 사용자 (있으면 그대로). */
    private long user(String loginId) {
        return jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, 'INVESTIGATOR', 1, 1)
                on conflict (login_id) do update set display_name = excluded.display_name
                returning id
                """, Long.class, loginId, loginId);
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
}
