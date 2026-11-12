package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
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

/**
 * ListPendingApprovals (P1) — 실제 PostgreSQL. 케이스 줄은 cases ListCasesPendingApproval(#101), 검증·반려·자기 승인 표시는 str 이 붙인다.
 * 승인 대기는 SubmitStrDraft(S6, #100)로 실제로 만든다.
 * 같은 컨테이너를 테스트끼리 같이 쓰므로, 개수·순서는 "이 테스트가 만든 초안"만 골라서 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ListPendingApprovalsDbTest {

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

    @Autowired ListPendingApprovals listPending;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired ReviseStrDraft reviseDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired ApprovalRepository approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long approver;
    long admin;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("lst-inv" + n, "INVESTIGATOR");
        approver = user("lst-apr" + n, "APPROVER");
        admin = user("lst-adm" + n, "ADMIN");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-04-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 950 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 정상_승인_대기만_요청_오래된_순으로_검증_담당자_요청_시각과_함께_본문_없이_온다() {
        long first = submittedDraft();
        long second = submittedDraft();
        long notSubmitted = savedDraft(escalatedCase());      // DRAFTED — 목록에 없다
        login(approver, "APPROVER");

        List<ListPendingApprovals.Item> mine = mine(listPending.execute(0, 100), first, second, notSubmitted);

        assertThat(mine).extracting(i -> i.draftId()).containsExactly(first, second);
        ListPendingApprovals.Item item = mine.get(0);
        assertThat(item.caseNo()).startsWith("CASE-2024-");
        assertThat(item.assignee().loginId()).startsWith("lst-inv");
        assertThat(item.submittedAt()).isNotNull();
        assertThat(item.caseVersion()).isEqualTo(caseVersionOfDraft(first));
        assertThat(item.verification().status()).isEqualTo("PASSED");
        assertThat(item.verification().passed()).isTrue();
        assertThat(item.verification().revisionNo()).isEqualTo(1);
        assertThat(item.lastRejection()).isNull();          // 처음 요청
        assertThat(item.wasAssignee()).isFalse();
        assertThat(item.availableActions()).containsExactly("APPROVE", "REJECT");
    }

    // 2
    @Test
    void 정상_보완_요청_뒤_고쳐서_다시_요청한_건은_last_rejection이_채워진다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        rejectNeedRevision(draftId, "근거 거래 설명 보완");       // 반려 당시 본문 = AI 원문
        login(investigator, "INVESTIGATOR");
        reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), Map.of(StrSection.METHOD, "고친 방법"));
        submit(caseId, draftId);                              // 본문이 바뀌어서 NARRATIVE_UNCHANGED 아님
        login(approver, "APPROVER");

        ListPendingApprovals.Item item = mine(listPending.execute(0, 100), draftId).get(0);

        assertThat(item.lastRejection().reasonCode()).isEqualTo("NEED_REVISION");
        assertThat(item.lastRejection().comment()).isEqualTo("근거 거래 설명 보완");
        assertThat(item.lastRejection().decidedAt()).isNotNull();
        assertThat(item.verification().revisionNo()).isEqualTo(2);   // 고친 수정본을 검증
    }

    // 3
    @Test
    void 정상_관리자는_목록만_보고_버튼은_없다() {
        long draftId = submittedDraft();
        login(admin, "ADMIN");

        ListPendingApprovals.Item item = mine(listPending.execute(0, 100), draftId).get(0);

        assertThat(item.availableActions()).isEmpty();
        assertThat(item.wasAssignee()).isFalse();
    }

    // 4
    @Test
    void 정상_예전에_담당했던_승인자에게는_직접_담당했던_케이스로_표시하고_버튼을_주지_않는다() {   // 미결 32
        long draftId = submittedDraft();
        long caseId = caseIdOfDraft(draftId);
        long formerAssignee = user("lst-apr-former" + SEQ.incrementAndGet(), "APPROVER");
        jdbc.update("insert into ops.case_history(case_id, action, from_status, to_status, case_version_before, "
                + "created_by, to_assignee_id) values (?, 'ASSIGN', 'OPEN', 'REVIEWING', 0, 1, ?)", caseId, formerAssignee);
        login(formerAssignee, "APPROVER");

        ListPendingApprovals.Item item = mine(listPending.execute(0, 100), draftId).get(0);

        assertThat(item.wasAssignee()).isTrue();
        assertThat(item.availableActions()).isEmpty();
    }

    // 5
    @Test
    void 정상_페이지를_나누면_size만큼_오고_total은_전체_승인_대기_수다() {
        submittedDraft();
        submittedDraft();
        login(approver, "APPROVER");
        long pendingCount = jdbc.queryForObject("select count(*) from ops.cases where status = 'PENDING_APPROVAL'",
                Long.class);

        Page<ListPendingApprovals.Item> page0 = listPending.execute(0, 1);
        Page<ListPendingApprovals.Item> page1 = listPending.execute(1, 1);

        assertThat(page0.getContent()).hasSize(1);
        assertThat(page1.getContent()).hasSize(1);
        assertThat(page0.getTotalElements()).isEqualTo(pendingCount);
        assertThat(page0.getContent().get(0).draftId()).isNotEqualTo(page1.getContent().get(0).draftId());
    }

    // 6
    @Test
    void 정상_검증이_여럿이면_가장_최근_검증을_보여_준다() {   // produced_at desc — S2·게이트와 같은 순서
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, 1, ?, 'LLM_FAILED', 'verify_v1', timestamptz '2026-11-01 00:00Z', '{}'::jsonb, ?)",
                "v-old-" + draftId, draftId, "g-" + draftId, currentHash(draftId), investigator);
        submit(caseId, draftId);                              // 여기서 넣는 PASSED 가 더 최근
        login(approver, "APPROVER");

        ListPendingApprovals.Item item = mine(listPending.execute(0, 100), draftId).get(0);

        assertThat(item.verification().status()).isEqualTo("PASSED");
        assertThat(item.verification().verificationId()).isNotEqualTo("v-old-" + draftId);
    }

    // ---- 도우미 ----

    private static List<ListPendingApprovals.Item> mine(Page<ListPendingApprovals.Item> page, Long... draftIds) {
        List<Long> ids = List.of(draftIds);
        return page.getContent().stream().filter(i -> ids.contains(i.draftId())).toList();
    }

    /** ESCALATED → 초안 저장(DRAFTED) → 검증 통과 → 승인 요청(PENDING_APPROVAL). */
    private long submittedDraft() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        return draftId;
    }

    private void submit(long caseId, long draftId) {
        String verificationId = passedVerification(draftId);
        login(investigator, "INVESTIGATOR");
        submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId, List.of("VI-1"), 3);
        SecurityContextHolder.clearContext();
    }

    private String passedVerification(long draftId) {
        record Body(String hash, int revisionNo) {}
        Body body = tx.execute(s -> {
            var d = drafts.findById(draftId).orElseThrow();
            return new Body(NarrativeHash.of(d.currentBody()), d.getCurrentRevision().getRevisionNo());
        });
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, ?, ?, 'PASSED', 'verify_v1', now(), '{}'::jsonb, ?)",
                verificationId, draftId, "g-" + draftId, body.revisionNo(), body.hash(), investigator);
        return verificationId;
    }

    private String currentHash(long draftId) {
        return tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
    }

    /** 반려 기록만 남기고(approvals) 케이스는 SQL 로 보완 요청 반려 상태로 — 반려 API(P3)는 다음 PR. */
    private void rejectNeedRevision(long draftId, String comment) {
        login(approver, "APPROVER");
        tx.executeWithoutResult(s -> approvals.save(Approval.rejected(drafts.findById(draftId).orElseThrow(),
                RejectionReasonCode.NEED_REVISION, comment)));
        SecurityContextHolder.clearContext();
        jdbc.update("update ops.cases set status = 'REJECTED', rejection_reason_code = 'NEED_REVISION', "
                + "version = version + 1 where id = ?", caseIdOfDraft(draftId));
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
                + "'2024-04-01 00:00', '2024-04-02 00:00', '2024-04-02 00:00', '{}', '2024-04-01', 'SCORED') returning id",
                Long.class, "ALT-20240401-" + String.format("%06d", 840_000 + n), batchRunId, ruleId, "ACC-L" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 840_000 + n), alertId, "ACC-L" + n, investigator);
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '사용자', ?, 1, 1) returning id", Long.class, loginId, role);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private long caseIdOfDraft(long draftId) {
        return jdbc.queryForObject("select case_id from ops.str_drafts where id = ?", Long.class, draftId);
    }

    private long caseVersionOfDraft(long draftId) {
        return caseVersion(caseIdOfDraft(draftId));
    }

    private static void login(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }
}
