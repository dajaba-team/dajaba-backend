package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * str 승인함(P1)용 공개 조회 ListCasesPendingApproval (SCRUM-222) — 실제 PostgreSQL.
 * 같은 DB 를 테스트끼리 나눠 쓰므로, 각 테스트는 자기 케이스만 골라 순서를 본다(요청 시각은 테스트마다 다른 해).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ListCasesPendingApprovalDbTest {

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

    @Autowired ListCasesPendingApproval listPending;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 정상_승인_대기만_요청_오래된_순으로_칸이_채워진다() {
        long inv = user("lp_inv");
        Case late = pending(inv, "2001-01-03T00:00:00Z");
        Case early = pending(inv, "2001-01-01T00:00:00Z");
        Case middle = pending(inv, "2001-01-02T00:00:00Z");
        Case drafted = withStatus(inv, CaseStatus.DRAFTED);
        submittedAt(drafted.getId(), "2001-01-01T12:00:00Z");   // 이력이 있어도 상태가 다르면 안 나온다
        Case approved = withStatus(inv, CaseStatus.APPROVED);
        submittedAt(approved.getId(), "2001-01-01T12:00:00Z");

        List<PendingCase> mine = mineOf(all(), Set.of(late.getId(), early.getId(), middle.getId(),
                drafted.getId(), approved.getId()));

        assertThat(mine).extracting(PendingCase::caseId)
                .containsExactly(early.getId(), middle.getId(), late.getId());
        PendingCase first = mine.get(0);
        Case stored = cases.findById(early.getId()).orElseThrow();
        assertThat(first.caseNo()).isEqualTo(stored.getCaseNo());
        assertThat(first.version()).isEqualTo(stored.getVersion());
        assertThat(first.currentDraftId()).isEqualTo(stored.getCurrentDraftId());
        assertThat(first.assignee().loginId()).startsWith("lp_inv");
        assertThat(first.assignee().displayName()).startsWith("이름 lp_inv");
        assertThat(first.submittedAt()).isEqualTo(Instant.parse("2001-01-01T00:00:00Z"));
    }

    @Test
    void 보완_뒤_다시_요청하면_마지막_요청_시각으로_정렬된다() {
        long inv = user("lp_re");
        Case resubmitted = pending(inv, "2002-01-01T00:00:00Z");
        submittedAt(resubmitted.getId(), "2002-01-05T00:00:00Z");   // 반려 → 보완 → 다시 요청
        Case other = pending(inv, "2002-01-03T00:00:00Z");

        List<PendingCase> mine = mineOf(all(), Set.of(resubmitted.getId(), other.getId()));

        assertThat(mine).extracting(PendingCase::caseId).containsExactly(other.getId(), resubmitted.getId());
        assertThat(mine.get(1).submittedAt()).isEqualTo(Instant.parse("2002-01-05T00:00:00Z"));
    }

    @Test
    void 요청_시각이_같으면_케이스_id_오름차순() {
        long inv = user("lp_tie");
        Case a = pending(inv, "2003-01-01T00:00:00Z");
        Case b = pending(inv, "2003-01-01T00:00:00Z");
        Case c = pending(inv, "2003-01-01T00:00:00Z");

        List<PendingCase> mine = mineOf(all(), Set.of(a.getId(), b.getId(), c.getId()));

        assertThat(mine).extracting(PendingCase::caseId).containsExactly(a.getId(), b.getId(), c.getId());
    }

    @Test
    void 페이지를_나눠도_중복_누락_없이_전체_순서와_같고_건수가_맞다() {
        long inv = user("lp_page");
        for (int i = 0; i < 5; i++) {
            pending(inv, "2004-01-0%dT00:00:00Z".formatted(5 - i));
        }
        List<PendingCase> whole = all();

        List<PendingCase> paged = new ArrayList<>();
        Page<PendingCase> p;
        int page = 0;
        do {
            p = listPending.execute(page++, 2);
            assertThat(p.getSize()).isEqualTo(2);
            assertThat(p.getTotalElements()).isEqualTo(whole.size());
            paged.addAll(p.getContent());
        } while (p.hasNext());

        assertThat(paged).containsExactlyElementsOf(whole);
        assertThat(listPending.execute(page + 10, 2).getContent()).isEmpty();
    }

    @Test
    void page_음수나_size_범위_밖이면_IllegalArgumentException() {
        assertThatThrownBy(() -> listPending.execute(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listPending.execute(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listPending.execute(0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(listPending.execute(0, 100).getSize()).isEqualTo(100);
    }

    @Test
    void 바깥_트랜잭션_안에서도_부를_수_있다() {
        long inv = user("lp_tx");
        Case c = pending(inv, "2005-01-01T00:00:00Z");

        Page<PendingCase> inTx = new TransactionTemplate(transactionManager)
                .execute(s -> listPending.execute(0, 100));

        assertThat(inTx.getContent()).extracting(PendingCase::caseId).contains(c.getId());
    }

    // ── 준비 ──────────────────────────────────────────────────

    /** 전체 페이지를 모아 한 목록으로. */
    private List<PendingCase> all() {
        List<PendingCase> out = new ArrayList<>();
        Page<PendingCase> p;
        int page = 0;
        do {
            p = listPending.execute(page++, 100);
            out.addAll(p.getContent());
        } while (p.hasNext());
        return out;
    }

    private static List<PendingCase> mineOf(List<PendingCase> rows, Set<Long> ids) {
        return rows.stream().filter(r -> ids.contains(r.caseId())).toList();
    }

    /** 승인 대기 케이스 + 그 시각의 SUBMIT_FOR_APPROVAL 이력 한 행. */
    private Case pending(long assigneeId, String submittedAt) {
        Case c = withStatus(assigneeId, CaseStatus.PENDING_APPROVAL);
        submittedAt(c.getId(), submittedAt);
        return c;
    }

    private void submittedAt(long caseId, String at) {
        jdbc.update("""
                insert into ops.case_history (case_id, action, from_status, to_status, case_version_before,
                                              created_at, created_by)
                values (?, 'SUBMIT_FOR_APPROVAL', 'DRAFTED', 'PENDING_APPROVAL', 0, ?, 1)
                """, caseId, Timestamp.from(Instant.parse(at)));
    }

    /** DB 제약(V006)에 맞게 칸을 채워 그 상태로 바꾼다 (CaseActiveCaseCounterDbTest 와 같음). */
    private Case withStatus(long assigneeId, CaseStatus status) {
        Case c = newCase();
        Long draftId = newDraft(c.getId());
        jdbc.update("update ops.cases set status = ?, assignee_id = ?, current_draft_id = ? where id = ?",
                status.name(), assigneeId, draftId, c.getId());
        return c;
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
        return new TransactionTemplate(transactionManager).execute(s -> {
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

    private long newAlert() {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-01-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_LP_" + n);
        return jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-01-02 00:00:00', '2023-01-02 23:59:59', '2023-01-02 23:59:59',
                        '{}'::jsonb, '2023-01-02')
                returning id
                """, Long.class, "ALT-20230103-%06d".formatted(n), batchId, ruleId, "011_U" + n);
    }
}
