package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CommentRequiredException;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.cases.domain.SelfApprovalException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.domain.DraftSupersededException;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * str 에 주는 cases 기능 — AttachDraftToCase(lock·execute), TouchCaseForRevision, CaseRepository.findForUpdate
 * (SCRUM-98), SubmitCaseForApproval·ApproveCase·RejectCase — 실제 PostgreSQL. str SaveStrDraft 대신 TransactionTemplate 으로 바깥 트랜잭션을 연다.
 * 초안은 V008 cases_current_draft_fk 때문에 진짜로 만든다(newDraft).
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

    // Flyway 는 DDL 소유자(app_migrate)로 (CaseMigrationDbTest 와 같음).
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired AttachDraftToCase attachDraft;
    @Autowired TouchCaseForRevision touchForRevision;
    @Autowired SubmitCaseForApproval submitForApproval;
    @Autowired ApproveCase approveCase;
    @Autowired RejectCase rejectCase;
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
        long d = newDraft(c.getId());
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(d);
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
        long d = newDraft(c.getId());
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(d);
    }

    @Test
    void 정상_보완_반려_뒤_새_초안이면_DRAFTED로_가고_반려_코드를_지운다() {
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        long d = newDraft(c.getId());
        loginAs(inv);

        inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion()));

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
        long d = newDraft(c.getId());
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_보고_대상_아님_반려에는_붙일_수_없다() {
        Case c = prepare(CaseStatus.REJECTED, RejectionReasonCode.NOT_REPORTABLE);
        long d = newDraft(c.getId());
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_담당자가_아니면_403_NOT_ASSIGNEE() {
        Case c = prepare(CaseStatus.ESCALATED);
        long d = newDraft(c.getId());
        loginAs(inv2);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion())))
                .isInstanceOfSatisfying(NotAssigneeException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_ASSIGNEE"));
        assertUnchanged(c);
    }

    @Test
    void 실패_AI_호출_사이에_케이스가_바뀌었으면_409_CONCURRENT_UPDATE() {
        Case c = prepare(CaseStatus.ESCALATED);
        long d = newDraft(c.getId());
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> attachDraft.execute(c.getId(), d, c.getVersion() - 1)))
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
        long d = newDraft(c.getId());
        loginAs(inv);

        assertThatThrownBy(() -> attachDraft.execute(c.getId(), d, c.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_바깥_트랜잭션이_롤백되면_케이스와_이력도_같이_롤백() {
        Case c = prepare(CaseStatus.ESCALATED);
        long d = newDraft(c.getId());
        loginAs(inv);

        tx().executeWithoutResult(s -> {
            attachDraft.execute(c.getId(), d, c.getVersion());
            s.setRollbackOnly();   // str 초안 저장이 실패한 경우
        });

        assertUnchanged(c);
    }

    // ── 동시 ──────────────────────────────────────────────────

    @Test
    void 경계_같은_케이스를_동시에_붙이면_뒤_요청은_잠금을_기다렸다가_409() throws Exception {
        Case c = prepare(CaseStatus.ESCALATED);
        long version = c.getVersion();
        long d1 = newDraft(c.getId());   // 잠금을 쥔 동안엔 초안을 못 넣으니(cases FK) 둘 다 미리 만든다
        long d2 = newDraft(c.getId());
        AppUserDetails me = new AppUserDetails(inv, "a_inv01", "a_inv01", "INVESTIGATOR");
        CountDownLatch firstLocked = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> {
                attachDraft.execute(c.getId(), d1, version);
                firstLocked.countDown();
                sleep(800);   // 잠금을 쥔 채로 커밋을 늦춘다
            });
        });
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> attachDraft.execute(c.getId(), d2, version));
        });

        first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OptimisticLockException.class);   // 기다린 뒤 본 version 이 이미 올라 있다
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(300);

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getCurrentDraftId()).isEqualTo(d1);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId()))
                .filteredOn(h -> h.getAction() == CaseHistoryAction.ATTACH_DRAFT).hasSize(1);
    }

    @Test
    void 경계_str_흐름대로_lock_초안_insert_execute를_동시에_하면_뒤_요청은_lock에서_기다렸다가_409() throws Exception {
        // #43 리뷰 1번: 초안 insert(FK → cases KEY SHARE) 뒤에 FOR UPDATE 를 잡으면 두 저장이 서로를 기다려 교착(40P01).
        // str 은 트랜잭션 맨 앞에서 lock 을 부르므로, 뒤 요청은 lock 에서 기다렸다가 version 검사에서 409 가 된다.
        Case c = prepare(CaseStatus.ESCALATED);
        long version = c.getVersion();
        AppUserDetails me = new AppUserDetails(inv, "a_inv01", "a_inv01", "INVESTIGATOR");
        CountDownLatch firstAttached = new CountDownLatch(1);
        AtomicLong firstDraft = new AtomicLong();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> {
                attachDraft.lock(c.getId(), version);
                long d = newDraft(c.getId());                 // str 초안 insert — 같은 트랜잭션
                attachDraft.execute(c.getId(), d, version);
                firstDraft.set(d);
                firstAttached.countDown();
                sleep(800);   // 잠금을 쥔 채로 커밋을 늦춘다
            });
        });
        assertThat(firstAttached.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            login(me);
            tx().executeWithoutResult(s -> {
                attachDraft.lock(c.getId(), version);         // 여기서 기다렸다가 409 — 초안 insert 까지 가지 않는다
                long d = newDraft(c.getId());
                attachDraft.execute(c.getId(), d, version);
            });
        });

        first.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OptimisticLockException.class);   // 교착(40P01)이 아니라 version 409

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(firstDraft.get());
        assertThat(jdbc.queryForObject("select count(*) from ops.str_drafts where case_id = ?", Long.class, c.getId()))
                .isEqualTo(1L);                                       // 뒤 요청의 초안은 만들어지지 않았다
    }

    // ── AttachDraftToCase.lock: 초안 넣기 전 확인 ─────────────

    @Test
    void 정상_lock은_확인만_하고_아무것도_바꾸지_않는다() {
        Case c = prepare(CaseStatus.ESCALATED);
        loginAs(inv);

        inTx(() -> attachDraft.lock(c.getId(), c.getVersion()));

        assertUnchanged(c);
        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void 실패_lock도_보고_대상_아님_반려나_담당_아님이면_초안을_넣기_전에_거부() {
        Case rejected = prepare(CaseStatus.REJECTED, RejectionReasonCode.NOT_REPORTABLE);
        Case other = prepare(CaseStatus.ESCALATED);

        loginAs(inv);
        assertThatThrownBy(() -> inTx(() -> attachDraft.lock(rejected.getId(), rejected.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        loginAs(inv2);
        assertThatThrownBy(() -> inTx(() -> attachDraft.lock(other.getId(), other.getVersion())))
                .isInstanceOf(NotAssigneeException.class);
        assertThatThrownBy(() -> attachDraft.lock(other.getId(), other.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);   // str 트랜잭션 밖
    }

    // ── TouchCaseForRevision: 수정본 저장 전 version 만 올리기 ──

    @Test
    void 정상_수정본_저장이면_version만_1_오르고_상태와_이력은_그대로() {
        Case c = prepare(CaseStatus.DRAFTED);
        int historyCount = histories.findByCaseIdOrderByIdAsc(c.getId()).size();
        loginAs(inv);

        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(c.getCurrentDraftId());
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(historyCount);
    }

    @Test
    void 실패_같은_version으로_두_번째_version_올리기는_409() {
        Case c = prepare(CaseStatus.DRAFTED);
        long seen = c.getVersion();   // 두 화면이 같은 version 을 들고 있다
        loginAs(inv);

        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), seen));
        assertThatThrownBy(() -> inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), seen)))
                .isInstanceOf(OptimisticLockException.class);

        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(seen + 1);
    }

    @Test
    void 실패_담당이_아니면_403_NOT_ASSIGNEE() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv2);

        assertThatThrownBy(() -> inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion())))
                .isInstanceOf(NotAssigneeException.class);
        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void 정상_보완_반려_뒤_첫_수정본이면_DRAFTED로_돌아가고_반려_코드를_지우고_REOPEN_DRAFT_이력_version_1_증가() {
        // #43 리뷰 2번, 채영 확인 A안: str 은 상태를 모르므로 수정본 저장 때 이것 하나만 부른다
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        int historyCount = histories.findByCaseIdOrderByIdAsc(c.getId()).size();
        loginAs(inv);

        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getRejectionReasonCode()).isNull();
        assertThat(after.getCurrentDraftId()).isEqualTo(c.getCurrentDraftId());
        assertThat(after.getVersion()).isEqualTo(c.getVersion() + 1);   // 상태 변경으로 한 번만 오른다(+2 아님)

        List<CaseHistory> rows = histories.findByCaseIdOrderByIdAsc(c.getId());
        assertThat(rows).hasSize(historyCount + 1);
        CaseHistory last = rows.get(rows.size() - 1);
        assertThat(last.getAction()).isEqualTo(CaseHistoryAction.REOPEN_DRAFT);
        assertThat(last.getFromStatus()).isEqualTo(CaseStatus.REJECTED);
        assertThat(last.getToStatus()).isEqualTo(CaseStatus.DRAFTED);
    }

    @Test
    void 정상_보완_반려에서_되돌린_뒤_두_번째_수정본은_version만_오른다() {
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        loginAs(inv);
        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));
        long version = cases.findById(c.getId()).orElseThrow().getVersion();
        int historyCount = histories.findByCaseIdOrderByIdAsc(c.getId()).size();

        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), version));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getVersion()).isEqualTo(version + 1);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(historyCount);
    }

    @Test
    void 실패_보고_대상_아님_반려나_승인_대기면_409이고_아무것도_안_바뀐다() {
        Case notReportable = prepare(CaseStatus.REJECTED, RejectionReasonCode.NOT_REPORTABLE);
        Case pending = prepare(CaseStatus.PENDING_APPROVAL);
        loginAs(inv);

        for (Case c : List.of(notReportable, pending)) {
            int historyCount = histories.findByCaseIdOrderByIdAsc(c.getId()).size();
            assertThatThrownBy(() -> inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion())))
                    .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                            e -> assertThat(e.details()).containsEntry("action", "REVISE_DRAFT"));
            Case after = cases.findById(c.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(c.getStatus());
            assertThat(after.getRejectionReasonCode()).isEqualTo(c.getRejectionReasonCode());
            assertThat(after.getVersion()).isEqualTo(c.getVersion());
            assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).hasSize(historyCount);
        }
    }

    @Test
    void 실패_보완_반려라도_현재_초안이_아니면_409_DRAFT_SUPERSEDED이고_반려_상태_그대로() {
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        long other = newDraft(c.getId());        // 같은 케이스의 다른 초안(현재 초안 아님)
        loginAs(inv);

        assertThatThrownBy(() -> inTx(() -> touchForRevision.execute(c.getId(), other, c.getVersion())))
                .isInstanceOfSatisfying(DraftSupersededException.class, e -> assertThat(e.details())
                        .containsEntry("current_draft_no", DisplayNumber.draftNo(c.getCurrentDraftId())));
        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.REJECTED);
        assertThat(after.getRejectionReasonCode()).isEqualTo(RejectionReasonCode.NEED_REVISION);
        assertThat(after.getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void 실패_재생성으로_대체된_옛_초안이면_409_DRAFT_SUPERSEDED() {
        Case c = prepare(CaseStatus.DRAFTED);
        long old = c.getCurrentDraftId();
        loginAs(inv);
        long current = newDraft(c.getId());
        inTx(() -> attachDraft.execute(c.getId(), current, c.getVersion()));   // 재생성으로 현재 초안이 바뀜
        long version = cases.findById(c.getId()).orElseThrow().getVersion();

        assertThatThrownBy(() -> inTx(() -> touchForRevision.execute(c.getId(), old, version)))
                .isInstanceOfSatisfying(DraftSupersededException.class, e -> assertThat(e.details())
                        .containsEntry("draft_no", DisplayNumber.draftNo(old))
                        .containsEntry("current_draft_no", DisplayNumber.draftNo(current)));
        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(version);
    }

    @Test
    void 실패_수정본_저장도_str_트랜잭션_밖에서는_거부() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);

        assertThatThrownBy(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    void 경계_바깥_트랜잭션이_롤백되면_version도_그대로() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);

        tx().executeWithoutResult(s -> {
            touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion());
            s.setRollbackOnly();
        });

        assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(c.getVersion());
    }

    // ── 새 cases.version 반환 (SCRUM-141 — str 이 응답 case_version 에 싣는다) ──

    @Test
    void 정상_초안을_붙이면_돌려준_version이_커밋된_cases_version과_같고_그걸로_다음_요청이_통과한다() {
        Case c = prepare(CaseStatus.ESCALATED);
        long d = newDraft(c.getId());
        loginAs(inv);

        long returned = tx().execute(s -> attachDraft.execute(c.getId(), d, c.getVersion()));

        assertThat(returned).isEqualTo(dbVersion(c.getId())).isEqualTo(c.getVersion() + 1);
        // 화면이 돌려받은 값을 들고 바로 수정본을 저장해도 409 가 나지 않는다
        long next = tx().execute(s -> touchForRevision.execute(c.getId(), d, returned));
        assertThat(next).isEqualTo(dbVersion(c.getId())).isEqualTo(returned + 1);
    }

    @Test
    void 정상_DRAFTED_수정본이면_돌려준_version이_커밋된_cases_version과_같다() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);

        long returned = tx().execute(s -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        assertThat(returned).isEqualTo(dbVersion(c.getId())).isEqualTo(c.getVersion() + 1);
    }

    @Test
    void 정상_보완_반려_뒤_첫_수정본이면_돌려준_version이_커밋된_cases_version과_같다() {
        Case c = prepare(CaseStatus.REJECTED);   // NEED_REVISION
        loginAs(inv);

        long returned = tx().execute(s -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        assertThat(returned).isEqualTo(dbVersion(c.getId())).isEqualTo(c.getVersion() + 1);
    }

    // ── 승인 요청·승인·반려 (SubmitCaseForApproval·ApproveCase·RejectCase) ──────

    @Test
    void 정상_승인_요청하면_PENDING_APPROVAL_이력_한_행이고_돌려준_version이_커밋된_값과_같다() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);

        long returned = tx().execute(s -> submitForApproval.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(returned).isEqualTo(after.getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.SUBMIT_FOR_APPROVAL);
            assertThat(h.getFromStatus()).isEqualTo(CaseStatus.DRAFTED);
            assertThat(h.getToStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
            assertThat(h.getCreatedBy()).isEqualTo(inv);
            assertThat(h.getCaseVersionBefore()).isEqualTo(c.getVersion());
        });
    }

    @Test
    void 실패_승인_요청은_담당이_아니면_403_version이_다르면_409_없는_케이스는_404() {
        Case c = prepare(CaseStatus.DRAFTED);

        loginAs(inv2);
        assertThatThrownBy(() -> inTx(() -> submitForApproval.execute(c.getId(), c.getCurrentDraftId(), c.getVersion())))
                .isInstanceOf(NotAssigneeException.class);
        loginAs(inv);
        assertThatThrownBy(() -> inTx(() -> submitForApproval.execute(c.getId(), c.getCurrentDraftId(), c.getVersion() - 1)))
                .isInstanceOf(OptimisticLockException.class);
        assertThatThrownBy(() -> inTx(() -> submitForApproval.execute(999_999_999L, c.getCurrentDraftId(), 0)))
                .isInstanceOf(CaseNotFoundException.class);
        assertUnchanged(c);
    }

    @Test
    void 실패_승인_요청은_현재_초안이_아니거나_DRAFTED가_아니면_409이고_아무것도_안_바뀐다() {
        Case c = prepare(CaseStatus.DRAFTED);
        long old = c.getCurrentDraftId();
        loginAs(inv);
        long current = newDraft(c.getId());
        inTx(() -> attachDraft.execute(c.getId(), current, c.getVersion()));   // 재생성으로 현재 초안이 바뀜
        long version = dbVersion(c.getId());

        assertThatThrownBy(() -> inTx(() -> submitForApproval.execute(c.getId(), old, version)))
                .isInstanceOfSatisfying(DraftSupersededException.class, e -> assertThat(e.details())
                        .containsEntry("draft_no", DisplayNumber.draftNo(old))
                        .containsEntry("current_draft_no", DisplayNumber.draftNo(current)));
        assertThat(dbVersion(c.getId())).isEqualTo(version);

        Case pending = prepare(CaseStatus.PENDING_APPROVAL);   // 이미 승인 대기 — 두 번 요청
        assertThatThrownBy(() -> inTx(() ->
                submitForApproval.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(dbVersion(pending.getId())).isEqualTo(pending.getVersion());
    }

    @Test
    void 경계_수정본_저장과_승인_요청이_같은_version으로_오면_뒤_요청은_409() {
        Case c = prepare(CaseStatus.DRAFTED);
        loginAs(inv);
        inTx(() -> touchForRevision.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        assertThatThrownBy(() -> inTx(() -> submitForApproval.execute(c.getId(), c.getCurrentDraftId(), c.getVersion())))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(cases.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.DRAFTED);
    }

    @Test
    void 정상_승인하면_APPROVED_이력에_승인자가_남고_돌려준_version이_커밋된_값과_같다() {
        Case c = prepare(CaseStatus.PENDING_APPROVAL);
        loginAs(apr);

        long returned = tx().execute(s -> approveCase.execute(c.getId(), c.getCurrentDraftId(), c.getVersion()));

        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.APPROVED);
        assertThat(returned).isEqualTo(after.getVersion()).isEqualTo(c.getVersion() + 1);
        assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
            assertThat(h.getAction()).isEqualTo(CaseHistoryAction.APPROVE);
            assertThat(h.getToStatus()).isEqualTo(CaseStatus.APPROVED);
            assertThat(h.getCreatedBy()).isEqualTo(apr);
        });
    }

    @Test
    void 실패_지금_담당이나_예전_담당은_승인도_반려도_409_SELF_APPROVAL() {
        Case c = prepare(CaseStatus.DRAFTED);
        tx().executeWithoutResult(s -> {            // inv → inv2 로 넘긴 뒤 inv2 가 승인 요청
            Case x = cases.findById(c.getId()).orElseThrow();
            x.reassign(inv2, apr, "담당 변경");
            x.submitForApproval(inv2);
            histories.saveAll(cases.saveAndFlush(x).takePendingHistories());
        });
        Case pending = cases.findById(c.getId()).orElseThrow();

        for (long who : List.of(inv, inv2)) {       // 예전 담당, 지금 담당
            loginAs(who);
            assertThatThrownBy(() -> inTx(() ->
                    approveCase.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion())))
                    .isInstanceOf(SelfApprovalException.class);
            assertThatThrownBy(() -> inTx(() -> rejectCase.execute(pending.getId(), pending.getCurrentDraftId(),
                    pending.getVersion(), RejectionReasonCode.NEED_REVISION, "보완 필요")))
                    .isInstanceOf(SelfApprovalException.class);
        }
        Case after = cases.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(after.getVersion()).isEqualTo(pending.getVersion());
    }

    @Test
    void 실패_승인은_승인_대기가_아니거나_현재_초안이_아니거나_version이_다르면_409() {
        Case drafted = prepare(CaseStatus.DRAFTED);
        Case pending = prepare(CaseStatus.PENDING_APPROVAL);
        long other = newDraft(drafted.getId());     // 다른 케이스의 초안
        loginAs(apr);

        assertThatThrownBy(() -> inTx(() ->
                approveCase.execute(drafted.getId(), drafted.getCurrentDraftId(), drafted.getVersion())))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> inTx(() -> approveCase.execute(pending.getId(), other, pending.getVersion())))
                .isInstanceOfSatisfying(DraftSupersededException.class, e -> assertThat(e.details())
                        .containsEntry("current_draft_no", DisplayNumber.draftNo(pending.getCurrentDraftId())));
        assertThatThrownBy(() -> inTx(() ->
                approveCase.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion() - 1)))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(cases.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(dbVersion(pending.getId())).isEqualTo(pending.getVersion());
    }

    @Test
    void 정상_반려하면_REJECTED_반려_코드가_케이스와_이력에_남는다() {
        for (RejectionReasonCode code : RejectionReasonCode.values()) {
            Case c = prepare(CaseStatus.PENDING_APPROVAL);
            loginAs(apr);

            long returned = tx().execute(s ->
                    rejectCase.execute(c.getId(), c.getCurrentDraftId(), c.getVersion(), code, "거래 목적 보완 필요"));

            Case after = cases.findById(c.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(CaseStatus.REJECTED);
            assertThat(after.getRejectionReasonCode()).isEqualTo(code);
            assertThat(returned).isEqualTo(after.getVersion()).isEqualTo(c.getVersion() + 1);
            assertThat(histories.findByCaseIdOrderByIdAsc(c.getId())).last().satisfies(h -> {
                assertThat(h.getAction()).isEqualTo(CaseHistoryAction.REJECT);
                assertThat(h.getReasonCode()).isEqualTo(code.name());
                assertThat(h.getComment()).isEqualTo("거래 목적 보완 필요");
                assertThat(h.getCreatedBy()).isEqualTo(apr);
            });
        }
    }

    @Test
    void 실패_반려는_사유가_비었거나_승인_대기가_아니면_거부되고_아무것도_안_바뀐다() {
        Case pending = prepare(CaseStatus.PENDING_APPROVAL);
        Case drafted = prepare(CaseStatus.DRAFTED);
        loginAs(apr);

        assertThatThrownBy(() -> inTx(() -> rejectCase.execute(pending.getId(), pending.getCurrentDraftId(),
                pending.getVersion(), RejectionReasonCode.NEED_REVISION, " ")))
                .isInstanceOf(CommentRequiredException.class);
        assertThatThrownBy(() -> inTx(() -> rejectCase.execute(drafted.getId(), drafted.getCurrentDraftId(),
                drafted.getVersion(), RejectionReasonCode.NOT_REPORTABLE, "보고 대상 아님")))
                .isInstanceOf(InvalidCaseTransitionException.class);
        long other = newDraft(drafted.getId());   // 현재 초안이 아닌 초안 → 사유 확인 전에 DRAFT_SUPERSEDED
        assertThatThrownBy(() -> inTx(() -> rejectCase.execute(pending.getId(), other,
                pending.getVersion(), RejectionReasonCode.NEED_REVISION, "거래 목적 보완 필요")))
                .isInstanceOfSatisfying(DraftSupersededException.class, e -> assertThat(e.details())
                        .containsEntry("current_draft_no", DisplayNumber.draftNo(pending.getCurrentDraftId())));
        Case after = cases.findById(pending.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(after.getRejectionReasonCode()).isNull();
        assertThat(after.getVersion()).isEqualTo(pending.getVersion());
    }

    @Test
    void 실패_승인_요청_승인_반려도_str_트랜잭션_밖에서는_거부되고_바깥이_롤백되면_같이_롤백() {
        Case drafted = prepare(CaseStatus.DRAFTED);
        Case pending = prepare(CaseStatus.PENDING_APPROVAL);
        loginAs(inv);
        assertThatThrownBy(() -> submitForApproval.execute(drafted.getId(), drafted.getCurrentDraftId(), drafted.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        loginAs(apr);
        assertThatThrownBy(() -> approveCase.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> rejectCase.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion(),
                RejectionReasonCode.NEED_REVISION, "보완 필요"))
                .isInstanceOf(IllegalTransactionStateException.class);

        int historyCount = histories.findByCaseIdOrderByIdAsc(pending.getId()).size();
        tx().executeWithoutResult(s -> {            // str 쪽(승인 기록 저장 등)이 실패한 경우
            approveCase.execute(pending.getId(), pending.getCurrentDraftId(), pending.getVersion());
            s.setRollbackOnly();
        });
        Case after = cases.findById(pending.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING_APPROVAL);
        assertThat(after.getVersion()).isEqualTo(pending.getVersion());
        assertThat(histories.findByCaseIdOrderByIdAsc(pending.getId())).hasSize(historyCount);
    }

    private long dbVersion(long caseId) {
        return cases.findById(caseId).orElseThrow().getVersion();
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
            if (target == CaseStatus.ESCALATED || target == CaseStatus.DRAFTED || target == CaseStatus.REJECTED
                    || target == CaseStatus.PENDING_APPROVAL) {
                c.escalate(inv, "STR 초안 작성 필요");
            }
            Case saved = cases.save(c);
            histories.saveAll(saved.takePendingHistories());
            return saved.getId();
        });
        // 초안이 있어야 하는 상태는 케이스 id 가 생긴 뒤 진짜 초안을 만들어 붙인다 (V008 cases_current_draft_fk).
        if (target == CaseStatus.DRAFTED || target == CaseStatus.REJECTED || target == CaseStatus.PENDING_APPROVAL) {
            long draftId = newDraft(id);
            tx().executeWithoutResult(s -> {
                Case c = cases.findById(id).orElseThrow();
                c.attachDraft(draftId, inv);
                if (target == CaseStatus.REJECTED || target == CaseStatus.PENDING_APPROVAL) {
                    c.submitForApproval(inv);
                }
                if (target == CaseStatus.REJECTED) {
                    c.reject(apr, false, rejection, "보완 필요");
                }
                Case saved = cases.save(c);
                histories.saveAll(saved.takePendingHistories());
            });
        }
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

    /**
     * 초안 요청 기록 → SUCCEEDED → 초안 (V008 의 str_drafts → str_draft_requests 복합 FK 순서, CaseMigrationDbTest 와 같음).
     * 케이스마다 ACTIVE 초안은 하나라(str_drafts_one_active_uq) str 재생성처럼 이전 ACTIVE 를 SUPERSEDED 로 먼저 바꾼다.
     */
    private long newDraft(long caseId) {
        jdbc.update("update ops.str_drafts set status = 'SUPERSEDED' where case_id = ? and status = 'ACTIVE'", caseId);
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
