package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.security.AppUserDetails;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * str 에 주는 cases 기능 — AttachDraftToCase, BumpCaseVersion, CaseRepository.findForUpdate (WBS 2.9.1) — 실제 PostgreSQL.
 * str SaveStrDraft 대신 TransactionTemplate 으로 바깥 트랜잭션을 연다. 초안 FK 는 V008 이라 아직 아무 번호나 된다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AttachDraftToCaseDbTest {

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

    // Flyway 는 DDL 소유자(app_migrate)로 (CaseSchemaDbTest 와 같음).
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired AttachDraftToCase attachDraft;
    @Autowired BumpCaseVersion bumpVersion;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private long inv;
    private long inv2;
    private long apr;

    @BeforeEach
    void users() {
        inv = user("a_inv01", "INVESTIGATOR");
        inv2 = user("a_inv02", "INVESTIGATOR");
        apr = user("a_apr01", "APPROVER");
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    @Test
    void 정상_ESCALATED에_첫_초안을_붙이면_DRAFTED_이력_한_행_version_증가() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), 300L, c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(300L);
        assertThat(after.getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.ATTACH_DRAFT);
            assertThat(h.getFromStatus()).isEqualTo(CaseStatus.ESCALATED);
            assertThat(h.getToStatus()).isEqualTo(CaseStatus.DRAFTED);
            assertThat(h.getCreatedBy()).isEqualTo(inv);
            assertThat(h.getCaseVersionBefore()).isEqualTo(c.getVersion());
        });
    }

    @Test
    void 정상_DRAFTED에서_재생성하면_새_초안으로_바뀌고_상태는_DRAFTED() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), 301L, c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(301L);
    }

    @Test
    void 정상_보완_반려_뒤_새_초안이면_DRAFTED로_가고_반려_코드를_지운다() {
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), 302L, c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getRejectionReasonCode()).isNull();
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId()))
                .extracting(CaseHistory::getAction)
                .contains(CaseHistoryAction.REJECT)                     // 과거 반려는 이력에 남는다
                .last().isEqualTo(CaseHistoryAction.ATTACH_DRAFT);
    }

    // ── 실패 ──────────────────────────────────────────────────

    @Test
    void 실패_상신_전_REVIEWING에는_붙일_수_없고_아무것도_안_바뀐다() {
        Case c = prepare(CaseStatus.REVIEWING);
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), 300L, c.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_보고_대상_아님_반려에는_붙일_수_없다() {
        Case c = prepare(CaseStatus.REJECTED, RejectionReasonCode.NOT_REPORTABLE);
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), 300L, c.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_담당자가_아니면_403_NOT_ASSIGNEE() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv2);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), 300L, c.getVersion())))
                .isInstanceOfSatisfying(NotAssigneeException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_ASSIGNEE"));
        assertUnchanged(c);
    }

    @Test
    void 실패_AI_호출_사이에_케이스가_바뀌었으면_409_CONCURRENT_UPDATE() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), 300L, c.getVersion() - 1)))
                .isInstanceOf(OptimisticLockException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_없는_케이스는_404() {
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(Long.MAX_VALUE, 300L, 0L)))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void 실패_str_트랜잭션_밖에서_혼자_부르면_거부된다() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv);

        assertThatThrownBy(() -> attachDraft.execute(c.getId(), 300L, c.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_바깥_트랜잭션이_롤백되면_케이스와_이력도_같이_롤백() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv);

        tx().executeWithoutResult(s -> {
            attachDraft.execute(c.getId(), 300L, c.getVersion());
            s.setRollbackOnly();   // str 초안 저장이 실패한 경우
        });

        assertUnchanged(c);
    }

    // ── 동시 ──────────────────────────────────────────────────

    @Test
    void 경계_같은_케이스를_동시에_붙이면_뒤_요청은_잠금을_기다렸다가_409() throws Exception {
        Case c = prepare(CaseStatus.ESCALATED);
        long version = c.getVersion();
        AppUserDetails me = new AppUserDetails(inv, "a_inv01", "a_inv01", "INVESTIGATOR");
        CountDownLatch firstLocked = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> {
                attachDraft.execute(c.getId(), 300L, version);
                firstLocked.countDown();
                sleep(800);   // 잠금을 쥔 채로 커밋을 늦춘다
            });
        });
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> attachDraft.execute(c.getId(), 301L, version));
        });

        first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OptimisticLockException.class);   // 기다린 뒤 본 version 이 이미 올라 있다
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(300);

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getCurrentDraftId()).isEqualTo(300L);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId()))
                .filteredOn(h -> h.getAction() == CaseHistoryAction.ATTACH_DRAFT).hasSize(1);
    }

    // ── BumpCaseVersion: version 만 올리기 ─────────────────────

    @Test
    void 정상_version만_1_오르고_상태와_이력은_그대로() {
        Case c = prepare(CaseStatus.DRAFTED);
        int historyCount = histories.findByCaseIdOrderByIdAsc(c.getId()).size();

        inTx(() -> bumpVersion.execute(c.getId(), c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(c.getCurrentDraftId());
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(historyCount);
    }

    @Test
    void 실패_수정본_저장과_승인_요청이_같은_version으로_오면_뒤쪽이_409() {
        Case c = prepare(CaseStatus.DRAFTED);
        long seen = c.getVersion();   // 두 화면이 같은 version 을 들고 있다
        loginAs(inv);

        inTx(() -> bumpVersion.execute(c.getId(), seen));                   // str 수정본 저장 (케이스 행은 안 바뀜)
        assertThatThrownBy(() -> inTx(() -> {                               // 같은 version 으로 들어온 다른 요청
            bumpVersion.execute(c.getId(), seen);
        })).isInstanceOf(OptimisticLockException.class);

        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(seen + 1);
    }

    @Test
    void 실패_version만_올리기도_str_트랜잭션_밖에서는_거부() {
        Case c = prepare(CaseStatus.DRAFTED);

        assertThatThrownBy(() -> bumpVersion.execute(c.getId(), c.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void 경계_바깥_트랜잭션이_롤백되면_version도_그대로() {
        Case c = prepare(CaseStatus.DRAFTED);

        tx().executeWithoutResult(s -> {
            bumpVersion.execute(c.getId(), c.getVersion());
            s.setRollbackOnly();
        });

        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    // ── 도우미 ────────────────────────────────────────────────

    private Case prepare(CaseStatus target) {
        return prepare(target, RejectionReasonCode.NEED_REVISION);
    }

    /** 허용된 길로만 target 상태까지 간 케이스를 저장해 돌려준다(저장 후 version 포함). */
    private Case prepare(CaseStatus target, RejectionReasonCode rejection) {
        long alertId = newAlert();
        Long id = tx().execute(s -> {
            Case c = Case.open("CASE-2022-%06d".formatted(SEQ.incrementAndGet()), alertId, "010_A", null, SYSTEM);
            if (target != CaseStatus.OPEN) c.assign(inv, inv);
            if (target == CaseStatus.ESCALATED || target == CaseStatus.DRAFTED || target == CaseStatus.REJECTED) {
                c.escalate(inv, "STR 초안 작성 필요");
            }
            if (target == CaseStatus.DRAFTED || target == CaseStatus.REJECTED) c.attachDraft(200L, inv);
            if (target == CaseStatus.REJECTED) {
                c.submitForApproval(inv);
                c.reject(apr, rejection, "보완 필요");
            }
            Case saved = cases.save(c);
            histories.saveAll(saved.takePendingHistories());
            return saved.getId();
        });
        return cases.findById(id).orElseThrow();
    }

    private void assertUnchanged(Case before) {
        Case now = cases.findById(before.getId()).orElseThrow();
        assertThat(now.getStatus()).isEqualTo(before.getStatus());
        assertThat(now.getCurrentDraftId()).isEqualTo(before.getCurrentDraftId());
        assertThat(now.getRejectionReasonCode()).isEqualTo(before.getRejectionReasonCode());
        assertThat(now.getVersion()).isEqualTo(before.getVersion());
        // 준비 단계(prepare)에서 초안을 붙였으면 ATTACH_DRAFT 1행, 아니면 0행 — 그 뒤로 늘지 않았어야 한다
        assertThat(histories.findByCaseIdOrderByIdAsc(before.getId()))
                .filteredOn(h -> h.getAction() == CaseHistoryAction.ATTACH_DRAFT)
                .hasSize(before.getCurrentDraftId() == null ? 0 : 1);
    }

    private void inTx(Runnable r) {
        tx().executeWithoutResult(s -> r.run());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void loginAs(long userId) {
        login(new AppUserDetails(userId, "u" + userId, "u" + userId, "INVESTIGATOR"));
    }

    private static void login(AppUserDetails me) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1)
                on conflict (login_id) do update set display_name = excluded.display_name
                returning id
                """, Long.class, loginId, loginId, role);
    }

    private long newAlert() {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2022-09-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_ATT_" + n);
        return jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2022-09-02 00:00:00', '2022-09-02 23:59:59', '2022-09-02 23:59:59',
                        '{}'::jsonb, '2022-09-02')
                returning id
                """, Long.class, "ALT-20220903-%06d".formatted(n), batchId, ruleId, "010_U" + n);
    }
}
