package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CloseCaseWithStrDraft (A6 초안 있는 종결) — 실제 PostgreSQL. 초안은 SaveStrDraft 로 만들고(케이스 DRAFTED),
 * 반려·승인 대기 같은 다른 상태는 SQL 로 맞춘다(전이 자체는 RejectStrDraft·SubmitStrDraft 테스트 몫).
 * 케이스 CLOSED 와 초안 SUPERSEDED 가 같이 남고, 실패하면 둘 다 그대로인지 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CloseCaseWithStrDraftDbTest {

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

    @Autowired CloseCaseWithStrDraft closeWithDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
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
        investigator = user("cls-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("cls-inv-other" + n, "INVESTIGATOR");
        approver = user("cls-apr" + n, "APPROVER");
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
    void 정상_DRAFTED_케이스를_닫으면_CLOSED_이력_사유와_초안_SUPERSEDED가_한_번에_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        StrCaseChange res = closeWithDraft.execute(caseNo(caseId), "FALSE_POSITIVE", "근거 거래가 정상 급여 이체", before);

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.version()).isEqualTo(before + 1).isEqualTo(caseVersion(caseId));
        assertThat(res.currentDraftNo()).isEqualTo(DisplayNumber.draftNo(draftId));   // 종결 뒤에도 남는다(V006)
        assertThat(res.rejectionReasonCode()).isNull();
        Map<String, Object> c = jdbc.queryForMap("select status, close_reason, current_draft_id from ops.cases where id = ?", caseId);
        assertThat(c).containsEntry("status", "CLOSED").containsEntry("close_reason", "FALSE_POSITIVE")
                .containsEntry("current_draft_id", draftId);
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'CLOSE' "
                + "and created_by = ?", Long.class, caseId, investigator)).isEqualTo(1);
        assertThat(draftStatus(draftId)).isEqualTo("SUPERSEDED");
    }

    // 2
    @Test
    void 정상_보고_안_함_반려도_닫을_수_있고_반려_코드는_지워진다() {   // 계약서 4절 — 조사역은 [종료]만
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        caseState(caseId, "REJECTED", "NOT_REPORTABLE");
        login(investigator, "INVESTIGATOR");

        StrCaseChange res = closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "승인자 판단에 따라 종결", caseVersion(caseId));

        assertThat(res.status()).isEqualTo("CLOSED");
        assertThat(res.rejectionReasonCode()).isNull();       // 과거 코드는 REJECT 이력·approvals 에 남는다
        assertThat(draftStatus(draftId)).isEqualTo("SUPERSEDED");
    }

    // ── 실패: 상태 ────────────────────────────────────────────

    // 3
    @Test
    void 실패_보완_요청_반려나_승인_대기면_409_INVALID_CASE_TRANSITION이고_초안은_ACTIVE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        for (String[] state : new String[][] {{"REJECTED", "NEED_REVISION"}, {"PENDING_APPROVAL", null}}) {
            caseState(caseId, state[0], state[1]);
            long before = caseVersion(caseId);

            assertThatThrownBy(() -> closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "닫기", before))
                    .as(state[0]).isInstanceOf(InvalidCaseTransitionException.class);

            assertThat(caseStatus(caseId)).isEqualTo(state[0]);
            assertThat(draftStatus(draftId)).isEqualTo("ACTIVE");
        }
    }

    // 4
    @Test
    void 실패_초안이_없는_케이스면_409_INVALID_CASE_TRANSITION() {   // 초안 없는 종결은 A5 /close
        long caseId = escalatedCase();                        // ESCALATED, 초안 없음
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "닫기", before))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(caseStatus(caseId)).isEqualTo("ESCALATED");
        assertThat(caseVersion(caseId)).isEqualTo(before);
    }

    // ── 실패: 사람·버전·입력 ───────────────────────────────────

    // 5
    @Test
    void 실패_담당이_아니면_403_NOT_ASSIGNEE이고_아무것도_안_바뀐다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long before = caseVersion(caseId);
        login(otherInvestigator, "INVESTIGATOR");

        assertThatThrownBy(() -> closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "닫기", before))
                .isInstanceOf(NotAssigneeException.class);

        assertNotClosed(caseId, draftId, before);
    }

    // 6
    @Test
    void 실패_version이_다르면_409_CONCURRENT_UPDATE이고_아무것도_안_바뀐다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "닫기", before - 1))
                .isInstanceOf(OptimisticLockException.class);

        assertNotClosed(caseId, draftId, before);
    }

    // 7
    @Test
    void 실패_설명이_공백이면_COMMENT_REQUIRED이고_아무것도_안_바뀐다() {   // 형식 400 은 api 가 먼저 — 여기는 도메인 안전장치
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long before = caseVersion(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> closeWithDraft.execute(caseNo(caseId), "NO_ACTION", "   ", before))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("COMMENT_REQUIRED"));

        assertNotClosed(caseId, draftId, before);
    }

    // 8
    @Test
    void 실패_없는_케이스_번호면_404_CASE_NOT_FOUND() {
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> closeWithDraft.execute("CASE-2099-999999", "NO_ACTION", "닫기", 0))
                .isInstanceOf(CaseNotFoundException.class);
    }

    // ---- 도우미 ----

    private void assertNotClosed(long caseId, long draftId, long caseVersionBefore) {
        assertThat(caseStatus(caseId)).isEqualTo("DRAFTED");
        assertThat(caseVersion(caseId)).isEqualTo(caseVersionBefore);
        assertThat(draftStatus(draftId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history where case_id = ? and action = 'CLOSE'",
                Long.class, caseId)).isZero();
    }

    /** 케이스 상태만 SQL 로 — 반려 코드는 REJECTED 일 때만(V006 CHECK). version 은 그대로. */
    private void caseState(long caseId, String status, String rejectionCode) {
        jdbc.update("update ops.cases set status = ?, rejection_reason_code = ? where id = ?", status, rejectionCode, caseId);
    }

    private String draftStatus(long draftId) {
        return jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class, draftId);
    }

    private String caseNo(long caseId) {
        return jdbc.queryForObject("select case_no from ops.cases where id = ?", String.class, caseId);
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
