package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.SelfApprovalException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftNotVerifiedException;
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
 * ApproveStrDraft (P2) — 실제 PostgreSQL. 승인 대기는 SubmitStrDraft(S6)로 실제로 만든다.
 * 승인 한 트랜잭션(approvals + 초안 LOCKED + 케이스 APPROVED + 이력)이 같이 남고, 실패하면 하나도 안 남는지 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ApproveStrDraftDbTest {

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
        investigator = user("apv-inv" + n, "INVESTIGATOR");
        approver = user("apv-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-05-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_000 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_승인하면_승인_기록_초안_LOCKED_케이스_APPROVED_이력_본점_정도가_한_번에_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long verificationRowId = submit(caseId, draftId);
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        StrCaseChange res = approveDraft.execute(DisplayNumber.draftNo(draftId), before, 4);

        assertThat(res.status()).isEqualTo("APPROVED");
        assertThat(res.version()).isEqualTo(before + 1);
        assertThat(caseStatus(caseId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForMap("select status, suspicion_level_hq from ops.str_drafts where id = ?", draftId))
                .containsEntry("status", "LOCKED").containsEntry("suspicion_level_hq", 4);
        Map<String, Object> a = jdbc.queryForMap("select decision, verification_result_id, revision_id, "
                + "narrative_sha256, reason_code, created_by from ops.approvals where draft_id = ?", draftId);
        assertThat(a).containsEntry("decision", "APPROVED")
                .containsEntry("verification_result_id", verificationRowId)    // 게이트가 고른 검증
                .containsEntry("revision_id", currentRevisionId(draftId))
                .containsEntry("narrative_sha256", currentHash(draftId))
                .containsEntry("created_by", approver);
        assertThat(a.get("reason_code")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'APPROVE' "
                + "and created_by = ?", Long.class, caseId, approver)).isEqualTo(1);
    }

    // 2
    @Test
    void 정상_승인된_뒤에는_본문을_고칠_수_없다() {   // 원칙 ③ — 케이스가 APPROVED 라 S3 가 케이스 단계에서 409
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        login(approver, "APPROVER");
        approveDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), 4);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "승인 뒤 고치기"))).isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(jdbc.queryForObject("select count(*) from ops.str_revisions where draft_id = ?", Long.class,
                draftId)).isEqualTo(1);
    }

    // ── 실패: 자기 승인 막기 (미결 32) ─────────────────────────

    // 3
    @Test
    void 실패_지금_담당이_승인자가_되어도_예전_담당_승인자도_409_SELF_APPROVAL이고_아무것도_안_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        long formerAssignee = user("apv-former" + SEQ.incrementAndGet(), "APPROVER");
        jdbc.update("insert into ops.case_history(case_id, action, from_status, to_status, case_version_before, "
                + "created_by, to_assignee_id) values (?, 'ASSIGN', 'OPEN', 'REVIEWING', 0, 1, ?)", caseId, formerAssignee);

        for (long who : List.of(investigator, formerAssignee)) {   // 지금 담당(승인자 역할로 로그인), 예전 담당
            login(who, "APPROVER");
            assertThatThrownBy(() -> approveDraft.execute(DisplayNumber.draftNo(draftId), before, 4))
                    .isInstanceOf(SelfApprovalException.class);
        }

        assertNothingApproved(caseId, draftId, before);
    }

    // ── 실패: 역할·케이스 ──────────────────────────────────────

    // 4
    @Test
    void 실패_승인자가_아니면_403_NOT_APPROVER이고_케이스를_잠그지도_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        long otherInvestigator = user("apv-inv-other" + SEQ.incrementAndGet(), "INVESTIGATOR");
        login(otherInvestigator, "INVESTIGATOR");

        assertThatThrownBy(() -> approveDraft.execute(DisplayNumber.draftNo(draftId), before, 4))
                .isInstanceOf(NotApproverException.class);

        assertNothingApproved(caseId, draftId, before);
    }

    // 5
    @Test
    void 실패_case_version이_다르면_409_CONCURRENT_UPDATE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        assertThatThrownBy(() -> approveDraft.execute(DisplayNumber.draftNo(draftId), before - 1, 4))
                .isInstanceOf(OptimisticLockException.class);

        assertNothingApproved(caseId, draftId, before);
    }

    // 6
    @Test
    void 실패_승인_대기가_아니면_409_INVALID_CASE_TRANSITION() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);                    // DRAFTED — 승인 요청 전
        login(approver, "APPROVER");

        assertThatThrownBy(() -> approveDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), 4))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(approvalCount(draftId)).isZero();
        assertThat(draftStatus(draftId)).isEqualTo("ACTIVE");
    }

    // 7
    @Test
    void 실패_지금_본문의_최신_검증이_통과가_아니면_409_DRAFT_NOT_VERIFIED이고_케이스_APPROVED도_되돌아간다() {
        // 순서 고정 (#106 리뷰 1번): ApproveCase 가 먼저 케이스를 APPROVED·version+1 로 바꾸고 flush 한 뒤 게이트가 막는다.
        // ApproveCase 는 Propagation.MANDATORY 라 같은 트랜잭션 — 게이트 예외로 그 변경까지 전부 롤백돼야 한다.
        // 순서를 바꾸거나 cases 쪽 전파 설정이 바뀌어 "케이스만 APPROVED" 가 남으면 이 테스트가 깨진다.
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);                              // 요청 때는 PASSED
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, 1, ?, 'VERIFY_UNAVAILABLE', 'verify_v1', clock_timestamp(), '{}'::jsonb, ?)",
                "v-late-" + draftId, draftId, "g-" + draftId, currentHash(draftId), investigator);
                                                              // 같은 본문의 더 최근 검증이 통과 아님 (정상 화면 흐름엔 없지만 게이트는 막아야 함)
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        assertThatThrownBy(() -> approveDraft.execute(DisplayNumber.draftNo(draftId), before, 4))
                .isInstanceOf(DraftNotVerifiedException.class);

        assertNothingApproved(caseId, draftId, before);       // PENDING_APPROVAL·version 그대로 = ApproveCase 변경도 롤백
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'APPROVE'",
                Long.class, caseId)).isZero();
    }

    // ---- 도우미 ----

    private void assertNothingApproved(long caseId, long draftId, long caseVersionBefore) {
        assertThat(caseStatus(caseId)).isEqualTo("PENDING_APPROVAL");
        assertThat(caseVersion(caseId)).isEqualTo(caseVersionBefore);
        assertThat(approvalCount(draftId)).isZero();
        assertThat(draftStatus(draftId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select suspicion_level_hq from ops.str_drafts where id = ?", Integer.class,
                draftId)).isNull();
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
