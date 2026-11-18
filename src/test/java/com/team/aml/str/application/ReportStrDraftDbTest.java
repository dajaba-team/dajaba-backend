package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NotDraftApproverException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NotApproverException;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReportStrDraft (P4 모의 제출) — 실제 PostgreSQL. 승인까지는 SubmitStrDraft(S6)·ApproveStrDraft(P2)로 실제로 만든다.
 * 제출 한 트랜잭션(케이스 REPORTED + REPORT 이력 + 확정 보고서 제출 기록)이 같이 남고, 실패하면 하나도 안 남는지 본다.
 * 제출자는 그 초안을 승인한 사람만 (11/17 결정).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReportStrDraftDbTest {

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

    @Autowired ReportStrDraft reportDraft;
    @Autowired ApproveStrDraft approveDraft;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired ReviseStrDraft reviseDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long approver;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("rpt-inv" + n, "INVESTIGATOR");
        approver = user("rpt-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-07-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_200 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_모의_제출하면_케이스_REPORTED_이력_보고서_제출_기록이_한_번에_남고_PDF는_PENDING() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long before = caseVersion(caseId);
        String reportNo = jdbc.queryForObject("select report_no from ops.str_reports where draft_id = ?", String.class, draftId);
        login(approver, "APPROVER");

        ReportStrDraft.Result r = reportDraft.execute(DisplayNumber.draftNo(draftId), before);

        assertThat(r.caseNo()).isEqualTo(caseNo(caseId));
        assertThat(r.caseStatus()).isEqualTo("REPORTED");
        assertThat(r.caseVersion()).isEqualTo(before + 1).isEqualTo(caseVersion(caseId));
        assertThat(r.draftNo()).isEqualTo(DisplayNumber.draftNo(draftId));
        assertThat(r.reportNo()).isEqualTo(reportNo);                            // 승인 때 매긴 번호 그대로
        assertThat(r.late()).isFalse();                                         // 승인 직후 제출 — 마감 전
        assertThat(r.pdfStatus().name()).isEqualTo("PENDING");

        assertThat(caseStatus(caseId)).isEqualTo("REPORTED");
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'REPORT' "
                + "and created_by = ?", Long.class, caseId, approver)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select reported_by, reported_at, late, pdf_status from ops.str_reports "
                + "where draft_id = ?", draftId);
        assertThat(row).containsEntry("reported_by", approver).containsEntry("late", false)
                .containsEntry("pdf_status", "PENDING");
        assertThat(row.get("reported_at")).isNotNull();
        assertThat(draftStatus(draftId)).isEqualTo("LOCKED");                   // 초안은 승인 때 잠긴 그대로
    }

    // 2
    @Test
    void 실패_승인하지_않은_다른_승인자는_403_NOT_DRAFT_APPROVER이고_아무것도_안_바뀐다() {   // 11/17 결정 — 승인한 사람만
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long before = caseVersion(caseId);
        long otherApprover = user("rpt-apr-other" + SEQ.incrementAndGet(), "APPROVER");
        login(otherApprover, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), before))
                .isInstanceOfSatisfying(NotDraftApproverException.class, e -> {
                    assertThat(e.code()).isEqualTo("NOT_DRAFT_APPROVER");
                    assertThat(e.details()).isEmpty();                     // 누가 승인했는지는 안 내보낸다
                });

        assertNothingReported(caseId, draftId, before);                    // ReportCase 가 바꾼 케이스도 롤백
    }

    // 3
    @Test
    void 정상_마감이_지난_뒤_제출하면_late가_true() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        // 승인일·마감을 과거로 옮긴다 (마감 계산 자체는 ReportDeadlineTest 몫). due > approved CHECK 는 지킨다
        jdbc.update("update ops.str_reports set approved_at = timestamptz '2026-01-02 01:00Z', "
                + "report_due_at = timestamptz '2026-01-07 14:59:59Z' where draft_id = ?", draftId);
        login(approver, "APPROVER");

        ReportStrDraft.Result r = reportDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId));

        assertThat(r.late()).isTrue();
        assertThat(jdbc.queryForObject("select late from ops.str_reports where draft_id = ?", Boolean.class, draftId)).isTrue();
    }

    // ── 실패 ──────────────────────────────────────────────────

    // 4
    @Test
    void 실패_이미_제출했으면_409_INVALID_CASE_TRANSITION이고_제출_기록은_처음_것_그대로() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        login(approver, "APPROVER");
        ReportStrDraft.Result first = reportDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId));
        String reportedAt = jdbc.queryForObject("select reported_at::text from ops.str_reports where draft_id = ?",
                String.class, draftId);

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), first.caseVersion()))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(jdbc.queryForObject("select reported_at::text from ops.str_reports where draft_id = ?",
                String.class, draftId)).isEqualTo(reportedAt);             // 제출 시각이 다시 찍히지 않는다
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'REPORT'",
                Long.class, caseId)).isEqualTo(1);
    }

    // 5
    @Test
    void 실패_승인_전이면_409_INVALID_CASE_TRANSITION() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);                              // PENDING_APPROVAL — 보고서도 아직 없다
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), before))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(caseStatus(caseId)).isEqualTo("PENDING_APPROVAL");
        assertThat(caseVersion(caseId)).isEqualTo(before);
    }

    // 6
    @Test
    void 실패_승인자가_아니면_403_NOT_APPROVER이고_아무것도_안_바뀐다() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), before))
                .isInstanceOf(NotApproverException.class);

        assertNothingReported(caseId, draftId, before);
    }

    // 7
    @Test
    void 실패_case_version이_다르면_409_CONCURRENT_UPDATE이고_아무것도_안_바뀐다() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), before - 1))
                .isInstanceOf(OptimisticLockException.class);

        assertNothingReported(caseId, draftId, before);
    }

    // 8
    @Test
    void 실패_없는_초안_번호면_404_DRAFT_NOT_FOUND() {
        login(approver, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute("DRF-999999", 0))
                .isInstanceOf(DraftNotFoundException.class);
    }

    // 9
    @Test
    void 실패_다른_승인자는_사본이_오래됐어도_이미_제출됐어도_상태_오류가_아니라_403이_먼저() {   // #125 리뷰 — 권한 → 상태 순
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long before = caseVersion(caseId);
        long otherApprover = user("rpt-apr-stale" + SEQ.incrementAndGet(), "APPROVER");
        login(otherApprover, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), before - 1))   // 오래된 사본
                .isInstanceOf(NotDraftApproverException.class);                                // 409 CONCURRENT_UPDATE 아님
        assertNothingReported(caseId, draftId, before);

        login(approver, "APPROVER");
        reportDraft.execute(DisplayNumber.draftNo(draftId), before);                           // 승인한 사람이 제출
        long reportedVersion = caseVersion(caseId);
        login(otherApprover, "APPROVER");

        assertThatThrownBy(() -> reportDraft.execute(DisplayNumber.draftNo(draftId), reportedVersion))
                .isInstanceOf(NotDraftApproverException.class);                                // 409 INVALID_CASE_TRANSITION 아님
    }

    // 10
    @Test
    void 정상_제출_시각은_REPORT_이력_시각과_같은_값이다() {   // #125 리뷰 — late 가 이력 시각과 어긋나지 않게
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        login(approver, "APPROVER");

        ReportStrDraft.Result r = reportDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId));

        Boolean same = jdbc.queryForObject("select rep.reported_at = h.created_at from ops.str_reports rep "
                + "join ops.case_history h on h.case_id = ? and h.action = 'REPORT' where rep.draft_id = ?",
                Boolean.class, caseId, draftId);
        assertThat(same).isTrue();                                       // DB 에 같은 값으로 남는다 (마이크로초까지)
        assertThat(r.reportedAt()).isNotNull();
    }

    // ---- 도우미 ----

    /** 케이스는 APPROVED 그대로, 보고서에는 제출 기록이 없다. */
    private void assertNothingReported(long caseId, long draftId, long caseVersionBefore) {
        assertThat(caseStatus(caseId)).isEqualTo("APPROVED");
        assertThat(caseVersion(caseId)).isEqualTo(caseVersionBefore);
        Map<String, Object> row = jdbc.queryForMap("select reported_at, reported_by, late, pdf_status from ops.str_reports "
                + "where draft_id = ?", draftId);
        assertThat(row.values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'REPORT'",
                Long.class, caseId)).isZero();
    }

    /** 초안 저장 → 승인 요청(S6) → 승인(P2, 본점 정도 4). 케이스 APPROVED, 초안 LOCKED, 확정 보고서 한 줄. */
    private long approvedDraft(long caseId) {
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        login(approver, "APPROVER");
        approveDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), 4);
        SecurityContextHolder.clearContext();
        return draftId;
    }

    private String caseNo(long caseId) {
        return jdbc.queryForObject("select case_no from ops.cases where id = ?", String.class, caseId);
    }

    private String accountKey(long caseId) {
        return jdbc.queryForObject("select account_key from ops.cases where id = ?", String.class, caseId);
    }

    /** 검증 통과 한 줄을 넣고 담당이 승인 요청(S6). 돌려주는 값 = 그 검증의 verification_results.id. */
    private long submit(long caseId, long draftId) {
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        long rowId = jdbc.queryForObject("insert into ops.verification_results(verification_id, draft_id, "
                        + "generation_id, revision_no, narrative_sha256, status, verifier_version, produced_at, payload, "
                        + "created_by) values (?, ?, ?, 1, ?, 'PASSED', 'verify_v1', now(), '{}'::jsonb, ?) returning id",
                Long.class, verificationId, draftId, "g-" + draftId, currentHash(draftId), investigator);
        login(investigator, "INVESTIGATOR");
        submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId, List.of("VI-1"), 3);
        SecurityContextHolder.clearContext();
        return rowId;
    }

    private String currentHash(long draftId) {
        return tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
    }

    private long currentRevisionId(long draftId) {
        return jdbc.queryForObject("select current_revision_id from ops.str_drafts where id = ?", Long.class, draftId);
    }

    private long approvalCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.approvals where draft_id = ?", Long.class, draftId);
    }

    private String draftStatus(long draftId) {
        return jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class, draftId);
    }

    private long savedDraft(long caseId) {
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", DisplayNumber.draftNo(draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        login(investigator, "INVESTIGATOR");
        saveDraft.execute(caseId, draftId, caseVersion(caseId), response(draftId));
        SecurityContextHolder.clearContext();
        return draftId;
    }

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

    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2024-05-01 00:00', '2024-05-02 00:00', '2024-05-02 00:00', '{}', '2024-05-01', 'SCORED') returning id",
                Long.class, "ALT-20240501-" + String.format("%06d", 850_000 + n), batchRunId, ruleId, "ACC-P" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 850_000 + n), alertId, "ACC-P" + n, investigator);
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
