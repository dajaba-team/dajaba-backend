package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.RevisionNotFoundException;
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
 * ReviseStrDraft (S3) · ListStrRevisions (S4) — 실제 PostgreSQL. 초안은 SaveStrDraft 로 만든다(GetStrDraftDbTest 와 같은 재료).
 * 수정 = 수정본 한 행 + 초안 version +1 + 케이스 version +1. 이력은 담당·승인자만, 그 밖 403.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReviseStrDraftDbTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT_KEY = "010_8000EBD30";

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

    @Autowired ReviseStrDraft revise;
    @Autowired ListStrRevisions revisions;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long otherInvestigator;
    long approver;
    long admin;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("rev-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("rev-inv-other" + n, "INVESTIGATOR");
        approver = user("rev-apr" + n, "APPROVER");
        admin = user("rev-adm" + n, "ADMIN");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-01-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 600 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── S3 정상 ───────────────────────────────────────────────

    // 1
    @Test
    void 정상_수정하면_수정본_2번이_생기고_초안과_케이스_version이_1씩_오른다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        long caseVersion = caseVersion(caseId);
        long draftVersion = draftVersion(draftId);
        login(investigator, "INVESTIGATOR");

        ReviseStrDraft.Revised r = revise.execute(DisplayNumber.draftNo(draftId), caseVersion,
                Map.of(StrSection.METHOD, "고친 방법 문장"));

        assertThat(r.revisionNo()).isEqualTo(2);
        assertThat(r.changedSections()).containsExactly(StrSection.METHOD);
        assertThat(r.needsVerification()).isTrue();
        assertThat(r.version()).isEqualTo(draftVersion + 1).isEqualTo(draftVersion(draftId));
        assertThat(r.caseVersion()).isEqualTo(caseVersion + 1).isEqualTo(caseVersion(caseId));
        assertThat(jdbc.queryForObject("select count(*) from ops.str_revisions where draft_id = ?", Long.class, draftId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select created_by from ops.str_revisions where draft_id = ? and revision_no = 2",
                Long.class, draftId)).isEqualTo(investigator);
    }

    // 2
    @Test
    void 정상_고친_단락만_문장이_바뀌고_근거_cites와_evidence_ids는_그대로다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), Map.of(StrSection.METHOD, "고친 방법 문장"));

        ListStrRevisions.Detail d = revisions.one(DisplayNumber.draftNo(draftId), 2);
        NarrativeSection method = section(d.after(), StrSection.METHOD);
        assertThat(method.text()).isEqualTo("고친 방법 문장");
        assertThat(method.cites()).containsExactly("period", "inbound.count");
        assertThat(method.evidenceIds()).containsExactly(104221L, 104233L);
        assertThat(section(d.after(), StrSection.DATE)).isEqualTo(section(d.before(), StrSection.DATE));
    }

    // 3
    @Test
    void 정상_보완_요청_반려_뒤_첫_수정이면_케이스가_DRAFTED로_돌아온다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("update ops.cases set status = 'REJECTED', rejection_reason_code = 'NEED_REVISION', "
                + "version = version + 1 where id = ?", caseId);
        login(investigator, "INVESTIGATOR");

        revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), Map.of(StrSection.GROUNDS, "보완한 사유"));

        assertThat(jdbc.queryForObject("select status from ops.cases where id = ?", String.class, caseId))
                .isEqualTo("DRAFTED");
        assertThat(jdbc.queryForObject("select rejection_reason_code from ops.cases where id = ?", String.class, caseId))
                .isNull();
    }

    // ── S3 실패 ───────────────────────────────────────────────

    // 4
    @Test
    void 실패_예전_case_version이면_409이고_수정본이_생기지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId) - 1,
                Map.of(StrSection.METHOD, "늦은 수정"))).isInstanceOf(OptimisticLockException.class);

        assertThat(revisionCount(draftId)).isEqualTo(1);
    }

    // 5
    @Test
    void 실패_담당이_아닌_조사역은_403_NOT_ASSIGNEE() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(otherInvestigator, "INVESTIGATOR");

        assertThatThrownBy(() -> revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "남의 수정"))).isInstanceOf(NotAssigneeException.class);

        assertThat(revisionCount(draftId)).isEqualTo(1);
    }

    // 6
    @Test
    void 실패_승인_대기_중이면_409_INVALID_CASE_TRANSITION() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("update ops.cases set status = 'PENDING_APPROVAL', version = version + 1 where id = ?", caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId),
                Map.of(StrSection.METHOD, "대기 중 수정"))).isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(revisionCount(draftId)).isEqualTo(1);
    }

    // ── S4 정상 ───────────────────────────────────────────────

    // 7
    @Test
    void 정상_이력_목록은_번호순이고_누가_어느_단락을_고쳤는지와_STR_VIEW_1행() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");
        revise.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), Map.of(StrSection.METHOD, "고친 방법 문장"));
        long viewsBefore = strViewCount(draftId);

        ListStrRevisions.Revisions list = revisions.list(DisplayNumber.draftNo(draftId));

        assertThat(list.currentRevisionNo()).isEqualTo(2);
        assertThat(list.revisions()).extracting(ListStrRevisions.Item::revisionNo).containsExactly(1, 2);
        assertThat(list.revisions().get(0).aiOriginal()).isTrue();
        assertThat(list.revisions().get(1).changedSections()).containsExactly(StrSection.METHOD);
        assertThat(list.revisions().get(1).revisedBy()).isEqualTo("사용자");
        assertThat(list.revisions().get(1).revisedAt()).isNotNull();
        assertThat(strViewCount(draftId)).isEqualTo(viewsBefore + 1);
    }

    // 8
    @Test
    void 정상_승인자도_전후_본문을_본다_1번은_before가_비어_있다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(approver, "APPROVER");

        ListStrRevisions.Detail first = revisions.one(DisplayNumber.draftNo(draftId), 1);

        assertThat(first.before()).isEmpty();
        assertThat(first.after()).hasSize(StrSection.V2.size());
    }

    // ── S4 실패 ───────────────────────────────────────────────

    // 9
    @Test
    void 실패_비담당_조사역과_ADMIN은_403이고_열람_기록이_없다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);

        login(otherInvestigator, "INVESTIGATOR");
        assertThatThrownBy(() -> revisions.list(DisplayNumber.draftNo(draftId))).isInstanceOf(NotAssigneeException.class);
        login(admin, "ADMIN");
        assertThatThrownBy(() -> revisions.one(DisplayNumber.draftNo(draftId), 1))
                .isInstanceOf(NotAssigneeException.class);

        assertThat(strViewCount(draftId)).isZero();
    }

    // 10
    @Test
    void 실패_없는_수정본_번호는_404_REVISION_NOT_FOUND() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> revisions.one(DisplayNumber.draftNo(draftId), 9))
                .isInstanceOf(RevisionNotFoundException.class);
    }

    // ---- 도우미 ----

    private static NarrativeSection section(java.util.List<NarrativeSection> body, StrSection s) {
        return body.stream().filter(n -> n.section() == s).findFirst().orElseThrow();
    }

    private long revisionCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.str_revisions where draft_id = ?", Long.class, draftId);
    }

    private long draftVersion(long draftId) {
        return jdbc.queryForObject("select version from ops.str_drafts where id = ?", Long.class, draftId);
    }

    private long strViewCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.access_logs where action='STR_VIEW' and target_id=?",
                Long.class, draftId);
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

    /** 규약 5.2 모양의 7항목 응답. facts 에 계좌 원문(subject.account_key, inbound.top[].from_account_key). */
    private static ObjectNode response(long draftId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", DisplayNumber.draftNo(draftId))
                .put("generation_id", "g-" + draftId);
        ObjectNode facts = r.putObject("facts");
        facts.putObject("subject").put("account_key", ACCOUNT_KEY);
        ObjectNode inbound = facts.putObject("inbound").put("count", 3);
        inbound.putArray("top").addObject().put("from_account_key", ACCOUNT_KEY).put("amount", 1000);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(104221).add(104233);
        }
        r.putNull("verification");
        return r;
    }

    /** 경보 → ESCALATED 케이스, 담당 = investigator. */
    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2024-01-01 00:00', '2024-01-02 00:00', '2024-01-02 00:00', '{}', '2024-01-01', 'SCORED') returning id",
                Long.class, "ALT-20240101-" + String.format("%06d", 810_000 + n), batchRunId, ruleId, "ACC-G" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 810_000 + n), alertId, "ACC-G" + n, investigator);
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '사용자', ?, 1, 1) returning id", Long.class, loginId, role);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private static void login(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }
}
