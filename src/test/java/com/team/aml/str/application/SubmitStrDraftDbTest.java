package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.DraftNotVerifiedException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeUnchangedException;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SubmitStrDraft (S6) — 실제 PostgreSQL. 초안은 SaveStrDraft 로 만들고(케이스 DRAFTED), 검증 결과는 SQL 로 한 줄 넣는다
 * (AI 를 부르지 않는다 — 게이트는 저장된 검증만 본다). 실패하면 케이스·초안이 하나도 안 바뀌는지까지 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SubmitStrDraftDbTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_backend");
        registry.add("spring.datasource.password", () -> "backend");
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired ReviseStrDraft reviseDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired ApprovalRepository approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long otherInvestigator;
    long approver;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("sub-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("sub-inv-other" + n, "INVESTIGATOR");
        approver = user("sub-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-03-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 900 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_지금_본문의_최신_검증이_통과면_PENDING_APPROVAL과_이력과_의심_유형_정도가_한_번에_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        StrCaseChange res = submitDraft.execute(DisplayNumber.draftNo(draftId), before, verificationId,
                List.of("VI-1", "VI-4"), 3);

        assertThat(res.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(res.version()).isEqualTo(before + 1);
        assertThat(res.currentDraftNo()).isEqualTo(DisplayNumber.draftNo(draftId));
        assertThat(res.assignee().loginId()).startsWith("sub-inv");
        assertThat(res.rejectionReasonCode()).isNull();
        assertThat(res.updatedAt().getNano()).isZero();                       // 초 단위 (계약서 2.4)

        assertThat(caseStatus(caseId)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? "
                + "and action = 'SUBMIT_FOR_APPROVAL' and created_by = ?", Long.class, caseId, investigator)).isEqualTo(1);
        Map<String, Object> d = jdbc.queryForMap("select array_to_string(suspicion_types, ',') types, "
                + "suspicion_level_branch, status from ops.str_drafts where id = ?", draftId);
        assertThat(d).containsEntry("types", "VI-1,VI-4").containsEntry("suspicion_level_branch", 3)
                .containsEntry("status", "ACTIVE");                            // 잠그는 건 승인(P2) 때
    }

    // ── 실패: 검증 (게이트 1~3번) ─────────────────────────────

    // 2
    @Test
    void 실패_검증이_없으면_409_DRAFT_NOT_VERIFIED이고_케이스와_초안이_그대로다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, "v-none",
                List.of("VI-1"), 3)).isInstanceOf(DraftNotVerifiedException.class);

        assertNothingChanged(caseId, draftId, before);
    }

    // 3
    @Test
    void 실패_검증_뒤에_본문을_고치면_예전_통과는_인정하지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        login(investigator, "INVESTIGATOR");
        reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "고친 문장"));
        long before = caseVersion(caseId);

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, verificationId,
                List.of("VI-1"), 3)).isInstanceOf(DraftNotVerifiedException.class);

        assertNothingChanged(caseId, draftId, before);
    }

    // 4
    @Test
    void 실패_같은_본문의_최신이_아닌_검증으로는_요청할_수_없다() {   // 다시 돌려 좋은 결과만 고르기 막기 (11/1)
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String olderPassed = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        String newerFailed = verification(draftId, "LLM_FAILED", "2026-11-12T02:00:00Z");
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, olderPassed,
                List.of("VI-1"), 3)).isInstanceOf(DraftNotVerifiedException.class);   // 예전 통과를 골라 보내도
        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, newerFailed,
                List.of("VI-1"), 3)).isInstanceOf(DraftNotVerifiedException.class);   // 최신이 불통과

        assertNothingChanged(caseId, draftId, before);
    }

    // ── 실패: 보완 요청 뒤 같은 본문 (게이트 4번) ──────────────

    // 5
    @Test
    void 실패_보완_요청_반려_때와_본문이_같으면_검증이_통과여도_409_NARRATIVE_UNCHANGED() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        needRevisionRejection(draftId);                       // 반려 당시 해시 = 지금 본문 해시
        String verificationId = verification(draftId, "PASSED", "2026-11-12T03:00:00Z");
        long before = caseVersion(caseId);                    // 케이스는 DRAFTED (같은 문장으로 다시 저장한 경우와 같음)
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, verificationId,
                List.of("VI-1"), 3)).isInstanceOf(NarrativeUnchangedException.class);

        assertNothingChanged(caseId, draftId, before);
    }

    // ── 실패: 케이스 (cases 공개 유스케이스) ───────────────────

    // 6
    @Test
    void 실패_담당이_아니면_검증을_보기_전에_403_NOT_ASSIGNEE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);                    // 검증 없음 — 그래도 403 이 먼저
        long before = caseVersion(caseId);
        login(otherInvestigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before, "v-none",
                List.of("VI-1"), 3)).isInstanceOf(NotAssigneeException.class);

        assertNothingChanged(caseId, draftId, before);
    }

    // 7
    @Test
    void 실패_case_version이_다르면_409_CONCURRENT_UPDATE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), before - 1, verificationId,
                List.of("VI-1"), 3)).isInstanceOf(OptimisticLockException.class);

        assertNothingChanged(caseId, draftId, before);
    }

    // 8
    @Test
    void 실패_이미_승인_대기면_다시_요청할_수_없다_409_INVALID_CASE_TRANSITION() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        login(investigator, "INVESTIGATOR");
        StrCaseChange first = submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                verificationId, List.of("VI-1"), 3);

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), first.version(),
                verificationId, List.of("VI-2"), 5)).isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(jdbc.queryForObject("select suspicion_level_branch from ops.str_drafts where id = ?",
                Integer.class, draftId)).isEqualTo(3);   // 두 번째 요청 값은 안 남는다
    }

    // ── 재생성으로 대체된 옛 초안 (#105 리뷰 1번) ─────────────

    // 9
    @Test
    void 확인_재생성으로_대체된_옛_초안으로_요청하면_지금은_409_INVALID_CASE_TRANSITION에_DB_숫자_id가_나간다() {
        // 계약서 3절은 409 DRAFT_SUPERSEDED + details.current_draft_no(화면 번호)인데, 케이스를 먼저 잠그는 순서라
        // cases 의 "현재 초안 아님"이 먼저 나간다. 맞추는 건 후속(지윤 님과 cases 예외 details 를 draft_no 로 바꿀지) — 그때 이 테스트를 고친다.
        long caseId = escalatedCase();
        long oldDraftId = savedDraft(caseId);
        String verificationId = verification(oldDraftId, "PASSED", "2026-11-12T01:00:00Z");
        long newDraftId = savedDraft(caseId);                 // 재생성 — 옛 초안 SUPERSEDED, 케이스 현재 초안 = 새 초안
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(oldDraftId), before, verificationId,
                List.of("VI-1"), 3))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> assertThat(e.details())
                        .containsEntry("draft_id", oldDraftId)            // DB 숫자 (DRF- 번호 아님)
                        .containsEntry("current_draft_id", newDraftId)
                        .doesNotContainKey("current_draft_no"));

        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
        assertThat(caseVersion(caseId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class, oldDraftId))
                .isEqualTo("SUPERSEDED");
    }

    // ── 본문 수정(S3)과 승인 요청(S6)이 겹칠 때 (미결 20, #105 리뷰 2번) ──

    // 10
    @Test
    void 동시_본문_수정이_먼저_커밋되면_같은_버전으로_온_승인_요청은_409_CONCURRENT_UPDATE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        long seen = caseVersion(caseId);                      // 두 화면이 같은 버전을 읽었다
        login(investigator, "INVESTIGATOR");

        reviseDraft.execute(DisplayNumber.draftNo(draftId), seen, Map.of(StrSection.METHOD, "먼저 고친 문장"));

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), seen, verificationId,
                List.of("VI-1"), 3)).isInstanceOf(OptimisticLockException.class);
        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
        assertThat(revisionCount(draftId)).isEqualTo(2);      // 수정은 남고, 고친 본문은 검증 전이라 요청은 안 됨
    }

    // 11
    @Test
    void 동시_승인_요청이_먼저_커밋되면_같은_버전으로_온_본문_수정은_409_CONCURRENT_UPDATE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        long seen = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        submitDraft.execute(DisplayNumber.draftNo(draftId), seen, verificationId, List.of("VI-1"), 3);

        assertThatThrownBy(() -> reviseDraft.execute(DisplayNumber.draftNo(draftId), seen,
                Map.of(StrSection.METHOD, "늦게 고친 문장"))).isInstanceOf(OptimisticLockException.class);
        assertThat(caseStatus(caseId)).isEqualTo("PENDING_APPROVAL");
        assertThat(revisionCount(draftId)).isEqualTo(1);      // 승인 대기 본문은 검증한 그대로
    }

    // 12
    @Test
    void 동시_두_요청을_실제로_겹쳐_보내면_하나만_성공하고_다른_하나는_409_CONCURRENT_UPDATE() throws Exception {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        String verificationId = verification(draftId, "PASSED", "2026-11-12T01:00:00Z");
        long seen = caseVersion(caseId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            results.add(pool.submit(asInvestigator(start, () -> reviseDraft.execute(DisplayNumber.draftNo(draftId),
                    seen, Map.of(StrSection.METHOD, "동시에 고친 문장")))));
            results.add(pool.submit(asInvestigator(start, () -> submitDraft.execute(DisplayNumber.draftNo(draftId),
                    seen, verificationId, List.of("VI-1"), 3))));
            start.countDown();                                // 둘을 한꺼번에 출발

            int succeeded = 0;
            int conflicted = 0;
            for (Future<Object> f : results) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockException.class);
                    conflicted++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(caseVersion(caseId)).isEqualTo(seen + 1);  // 이긴 쪽 한 번만 올랐다
        // 어느 쪽이 이겼든 "승인 대기인데 본문이 바뀐" 상태는 없다
        boolean pending = caseStatus(caseId).equals("PENDING_APPROVAL");
        assertThat(revisionCount(draftId)).isEqualTo(pending ? 1 : 2);
    }

    // ---- 도우미 ----

    private void assertNothingChanged(long caseId, long draftId, long caseVersionBefore) {
        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
        assertThat(caseVersion(caseId)).isEqualTo(caseVersionBefore);
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? "
                + "and action = 'SUBMIT_FOR_APPROVAL'", Long.class, caseId)).isZero();
        assertThat(jdbc.queryForObject("select cardinality(suspicion_types) from ops.str_drafts where id = ?",
                Integer.class, draftId)).isZero();
    }

    /** 다른 스레드에서 담당 조사역으로 로그인해 출발 신호를 기다렸다가 실행 (SecurityContext 는 스레드마다 따로). */
    private Callable<Object> asInvestigator(CountDownLatch start, Callable<Object> work) {
        return () -> {
            login(investigator, "INVESTIGATOR");
            try {
                start.await();
                return work.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private long revisionCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.str_revisions where draft_id = ?", Long.class, draftId);
    }

    /** 지금 본문 해시로 검증 결과 한 줄 (AI 대신). 돌려주는 값 = verification_id. */
    private String verification(long draftId, String status, String producedAt) {
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, 1, ?, ?, 'verify_v1', ?::timestamptz, '{}'::jsonb, ?)",
                verificationId, draftId, "g-" + draftId, currentHash(draftId), status, producedAt, investigator);
        return verificationId;
    }

    private String currentHash(long draftId) {
        return tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
    }

    /** 승인자가 지금 본문으로 보완 요청 반려 한 줄 (approvals). created_by 는 auditing 이 로그인한 승인자로 채운다. */
    private void needRevisionRejection(long draftId) {
        login(approver, "APPROVER");
        tx.executeWithoutResult(s -> approvals.save(Approval.rejected(drafts.findById(draftId).orElseThrow(),
                RejectionReasonCode.NEED_REVISION, "근거 보완 필요")));
        SecurityContextHolder.clearContext();
    }

    /** 담당 조사역으로 SaveStrDraft — 케이스 DRAFTED, 초안 ACTIVE, 수정본 1번. 끝나면 로그아웃. */
    private long savedDraft(long caseId) {
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", DisplayNumber.draftNo(draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        login(investigator, "INVESTIGATOR");
        saveDraft.execute(caseId, draftId, caseVersion(caseId), response(draftId));
        SecurityContextHolder.clearContext();
        return draftId;
    }

    /** 규약 5.2 모양의 7항목 응답. */
    private static ObjectNode response(long draftId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", DisplayNumber.draftNo(draftId))
                .put("generation_id", "g-" + draftId);
        r.putObject("facts").putObject("inbound").put("count", 3);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("inbound.count");
            n.putArray("evidence_ids").add(104221);
        }
        r.putNull("verification");
        return r;
    }

    /** 경보 → ESCALATED 케이스, 담당 = investigator. */
    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2024-03-01 00:00', '2024-03-02 00:00', '2024-03-02 00:00', '{}', '2024-03-01', 'SCORED') returning id",
                Long.class, "ALT-20240301-" + String.format("%06d", 830_000 + n), batchRunId, ruleId, "ACC-S" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 830_000 + n), alertId, "ACC-S" + n, investigator);
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '사용자', ?, 1, 1) returning id", Long.class, loginId, role);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private String caseStatus(long caseId) {
        return jdbc.queryForObject("select status from ops.cases where id = ?", String.class, caseId);
    }

    private static void login(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }
}
