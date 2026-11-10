package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.user.application.ActiveCaseCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 진행 중 담당 건수 — user.ActiveCaseCounter 의 cases 구현 (미결 33, SCRUM-178). 실제 PostgreSQL. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CaseActiveCaseCounterDbTest {

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

    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired ActiveCaseCounter counter;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 구현은_cases_의_것() {
        assertThat(counter).isInstanceOf(CaseActiveCaseCounter.class);
    }

    @Test
    void 정상_여러_명을_한_번에_세고_0건인_사람도_키가_있다() {
        long a = user("ac_a"), b = user("ac_b"), none = user("ac_none");
        withStatus(a, CaseStatus.REVIEWING);
        withStatus(a, CaseStatus.ESCALATED);
        withStatus(a, CaseStatus.DRAFTED);
        withStatus(b, CaseStatus.PENDING_APPROVAL);
        withStatus(b, CaseStatus.REJECTED);

        Map<Long, Long> counts = counter.countActiveByAssignees(List.of(a, b, none));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(a, 3L, b, 2L, none, 0L));
    }

    @Test
    void 종료_승인_제출된_케이스는_안_센다() {
        long a = user("ac_done");
        withStatus(a, CaseStatus.REVIEWING);
        withStatus(a, CaseStatus.CLOSED);
        withStatus(a, CaseStatus.APPROVED);
        withStatus(a, CaseStatus.REPORTED);
        newCase();   // OPEN — 담당 없음

        assertThat(counter.countActiveByAssignees(List.of(a))).containsExactly(Map.entry(a, 1L));
        assertThat(counter.countActive(a)).isEqualTo(1L);
    }

    @Test
    void 빈_목록이나_null_이면_빈_지도_null_id_와_중복은_건너뛴다() {
        long a = user("ac_dup");
        withStatus(a, CaseStatus.REVIEWING);

        assertThat(counter.countActiveByAssignees(List.of())).isEmpty();
        assertThat(counter.countActiveByAssignees(null)).isEmpty();   // #86 리뷰 — NPE 대신 빈 지도
        assertThat(counter.countActiveByAssignees(Arrays.asList(a, null, a))).containsExactly(Map.entry(a, 1L));
    }

    @Test
    void countActive_는_0건이면_0() {
        long a = user("ac_zero");
        assertThat(counter.countActive(a)).isZero();
        assertThat(counter.countActive(null)).isZero();
    }

    /** DB 제약(V006)에 맞게 칸을 채워 그 상태로 바꾼다 — 초안·종결 사유·반려 코드. */
    private void withStatus(long assigneeId, CaseStatus status) {
        Case c = newCase();
        boolean needsDraft = Set.of(CaseStatus.DRAFTED, CaseStatus.PENDING_APPROVAL, CaseStatus.APPROVED,
                CaseStatus.REPORTED, CaseStatus.REJECTED).contains(status);
        Long draftId = needsDraft ? newDraft(c.getId()) : null;
        jdbc.update("""
                update ops.cases set status = ?, assignee_id = ?, current_draft_id = ?,
                                     close_reason = ?, rejection_reason_code = ?
                where id = ?
                """, status.name(), assigneeId, draftId,
                status == CaseStatus.CLOSED ? "FALSE_POSITIVE" : null,
                status == CaseStatus.REJECTED ? "NEED_REVISION" : null,
                c.getId());
    }

    private long newDraft(long caseId) {
        long draftId = jdbc.queryForObject("select nextval('ops.str_draft_id_seq')", Long.class);
        jdbc.update("""
                insert into ops.str_draft_requests (draft_id, case_id, request_snapshot, created_by, updated_by)
                values (?, ?, '{}'::jsonb, 1, 1)
                """, draftId, caseId);
        jdbc.update("update ops.str_draft_requests set status = 'SUCCEEDED', finished_at = now() where draft_id = ?", draftId);
        jdbc.update("""
                insert into ops.str_drafts (id, case_id, generation_id, generator_version, produced_at, payload,
                                            created_by, updated_by)
                values (?, ?, ?, 'test', '2026-11-03T01:10:00Z', '{"facts":{}}'::jsonb, 1, 1)
                """, draftId, caseId, "g-" + draftId);
        return draftId;
    }

    private Case newCase() {
        long alertId = newAlert();
        return tx().execute(s -> {
            Case c = Case.open("CASE-2024-%06d".formatted(SEQ.incrementAndGet()), alertId, "011_U" + alertId, null, SYSTEM);
            Case saved = cases.saveAndFlush(c);
            histories.saveAll(saved.takePendingHistories());
            return saved;
        });
    }

    private long user(String prefix) {
        String loginId = prefix + SEQ.incrementAndGet();
        return jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, 'INVESTIGATOR', 1, 1) returning id
                """, Long.class, loginId, "이름 " + loginId);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long newAlert() {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-01-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_AC_" + n);
        return jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-01-02 00:00:00', '2023-01-02 23:59:59', '2023-01-02 23:59:59',
                        '{}'::jsonb, '2023-01-02')
                returning id
                """, Long.class, "ALT-20230103-%06d".formatted(n), batchId, ruleId, "011_U" + n);
    }
}
