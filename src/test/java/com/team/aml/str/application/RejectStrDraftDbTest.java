package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.SelfApprovalException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeUnchangedException;
import com.team.aml.str.domain.NotApproverException;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
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
 * RejectStrDraft (P3) — 실제 PostgreSQL. 반려 한 줄(approvals)과 케이스 REJECTED·반려 코드가 같이 남는지,
 * 그리고 반려 뒤 조사역 흐름(보완 요청 → 저장 → 재검증 → 재요청 / 보고 안 함 → 수정 불가)을 실제 유스케이스로 이어서 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RejectStrDraftDbTest {

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

    @Autowired RejectStrDraft rejectDraft;
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
        investigator = user("rej-inv" + n, "INVESTIGATOR");
        approver = user("rej-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-06-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_100 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_보완_요청은_반려_기록과_케이스_REJECTED_반려_코드_이력이_한_번에_남고_초안은_ACTIVE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        login(approver, "APPROVER");

        StrCaseChange res = rejectDraft.execute(DisplayNumber.draftNo(draftId), before, "NEED_REVISION", "근거 보완");

        assertThat(res.status()).isEqualTo("REJECTED");
        assertThat(res.rejectionReasonCode()).isEqualTo("NEED_REVISION");
        assertThat(res.version()).isEqualTo(before + 1);
        Map<String, Object> a = jdbc.queryForMap("select decision, reason_code, comment, narrative_sha256, "
                + "verification_result_id, revision_id, created_by from ops.approvals where draft_id = ?", draftId);
        assertThat(a).containsEntry("decision", "REJECTED").containsEntry("reason_code", "NEED_REVISION")
                .containsEntry("comment", "근거 보완")
                .containsEntry("narrative_sha256", currentHash(draftId))    // 다음 요청 때 NARRATIVE_UNCHANGED 기준
                .containsEntry("created_by", approver);
        assertThat(a.get("verification_result_id")).isNull();              // 반려는 근거 검증 없음 (V009 CHECK)
        assertThat(jdbc.queryForObject("select rejection_reason_code from ops.cases where id = ?", String.class,
                caseId)).isEqualTo("NEED_REVISION");
        assertThat(jdbc.queryForObject("select reason_code from ops.case_history where case_id = ? and action = 'REJECT'",
                String.class, caseId)).isEqualTo("NEED_REVISION");
        assertThat(draftStatus(draftId)).isEqualTo("ACTIVE");
    }

    // 2
    @Test
    void 정상_보완_요청_뒤_같은_문장으로_다시_저장하고_검증해도_재요청은_409_NARRATIVE_UNCHANGED() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        reject(caseId, draftId, "NEED_REVISION");
        login(investigator, "INVESTIGATOR");

        // 저장 전: 케이스가 REJECTED 라 승인 요청 자체가 안 된다 (화면은 버튼을 끈다)
        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), "v-x",
                List.of("VI-1"), 3)).isInstanceOf(InvalidCaseTransitionException.class);

        // 같은 문장으로 저장 → DRAFTED(REOPEN_DRAFT). 본문 해시는 반려 때와 같다
        reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "AI METHOD 문장"));
        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
        String verificationId = passedVerification(draftId);

        assertThatThrownBy(() -> submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                verificationId, List.of("VI-1"), 3)).isInstanceOf(NarrativeUnchangedException.class);
        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
    }

    // 3
    @Test
    void 정상_보완_요청_뒤_고쳐서_저장하고_다시_검증하면_재요청할_수_있다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        reject(caseId, draftId, "NEED_REVISION");
        login(investigator, "INVESTIGATOR");
        reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "근거 거래 3건을 시간 순으로 정리한 문장"));
        String verificationId = passedVerification(draftId);

        StrCaseChange res = submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId,
                List.of("VI-1"), 3);

        assertThat(res.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(res.rejectionReasonCode()).isNull();                    // DRAFTED 로 돌아갈 때 지워졌다
    }

    // 4
    @Test
    void 정상_보고_안_함이면_케이스에_NOT_REPORTABLE이_남고_본문을_고칠_수_없다() {   // 조사역은 [종료]만
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        StrCaseChange res = reject(caseId, draftId, "NOT_REPORTABLE");
        login(investigator, "INVESTIGATOR");

        assertThat(res.rejectionReasonCode()).isEqualTo("NOT_REPORTABLE");
        assertThatThrownBy(() -> reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "고치기"))).isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 실패 ──────────────────────────────────────────────────

    // 5
    @Test
    void 실패_지금_담당이나_예전_담당이_반려해도_409_SELF_APPROVAL이고_아무것도_안_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        long formerAssignee = user("rej-former" + SEQ.incrementAndGet(), "APPROVER");
        jdbc.update("insert into ops.case_history(case_id, action, from_status, to_status, case_version_before, "
                + "created_by, to_assignee_id) values (?, 'ASSIGN', 'OPEN', 'REVIEWING', 0, 1, ?)", caseId, formerAssignee);

        for (long who : List.of(investigator, formerAssignee)) {
            login(who, "APPROVER");
            assertThatThrownBy(() -> rejectDraft.execute(DisplayNumber.draftNo(draftId), before, "NEED_REVISION",
                    "보완")).isInstanceOf(SelfApprovalException.class);
        }

        assertNothingRejected(caseId, draftId, before);
    }

    // 6
    @Test
    void 실패_승인자가_아니면_403_NOT_APPROVER() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> rejectDraft.execute(DisplayNumber.draftNo(draftId), before, "NEED_REVISION", "보완"))
                .isInstanceOf(NotApproverException.class);

        assertNothingRejected(caseId, draftId, before);
    }

    // ---- 도우미 ----

    private void assertNothingRejected(long caseId, long draftId, long caseVersionBefore) {
        assertThat(caseStatus(caseId)).isEqualTo("PENDING_APPROVAL");
        assertThat(caseVersion(caseId)).isEqualTo(caseVersionBefore);
        assertThat(jdbc.queryForObject("select count(*) from ops.approvals where draft_id = ?", Long.class, draftId))
                .isZero();
    }

    private StrCaseChange reject(long caseId, long draftId, String code) {
        login(approver, "APPROVER");
        StrCaseChange res = rejectDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), code, "반려 사유");
        SecurityContextHolder.clearContext();
        return res;
    }

    private void submit(long caseId, long draftId) {
        String verificationId = passedVerification(draftId);
        login(investigator, "INVESTIGATOR");
        submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId, List.of("VI-1"), 3);
        SecurityContextHolder.clearContext();
    }

    /** 지금 본문으로 검증 통과 한 줄 (AI 대신). 매번 produced_at = now() 라 나중 것이 최신. */
    private String passedVerification(long draftId) {
        record Body(String hash, int revisionNo) {}
        Body body = tx.execute(s -> {
            var d = drafts.findById(draftId).orElseThrow();
            return new Body(NarrativeHash.of(d.currentBody()), d.getCurrentRevision().getRevisionNo());
        });
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, ?, ?, 'PASSED', 'verify_v1', clock_timestamp(), '{}'::jsonb, ?)",
                verificationId, draftId, "g-" + draftId, body.revisionNo(), body.hash(), investigator);
        return verificationId;
    }

    private String currentHash(long draftId) {
        return tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
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
                + "'2024-06-01 00:00', '2024-06-02 00:00', '2024-06-02 00:00', '{}', '2024-06-01', 'SCORED') returning id",
                Long.class, "ALT-20240601-" + String.format("%06d", 860_000 + n), batchRunId, ruleId, "ACC-R" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 860_000 + n), alertId, "ACC-R" + n, investigator);
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
