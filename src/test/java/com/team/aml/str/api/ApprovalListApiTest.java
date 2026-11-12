package com.team.aml.str.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.application.ReviseStrDraft;
import com.team.aml.str.application.SaveStrDraft;
import com.team.aml.str.application.SubmitStrDraft;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/v1/approvals (P1) — 실제 PostgreSQL + 필터 체인 전체 (#103 리뷰 1번).
 * 화면(MSW 목업)이 받는 JSON 모양 그대로를 본다: snake_case 키 집합이 계약서 P1과 같은지, 시각이 초 단위 Z인지,
 * 역할별 200·403·401, size 범위 400. 유스케이스 동작(순서·자기 승인·최신 검증)은 ListPendingApprovalsDbTest 몫.
 * 승인 대기는 SaveStrDraft → SubmitStrDraft(S6) 로 실제로 만든다 (담당 = inv01). 화면 요청은 apr01·admin·inv01 로그인 세션.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + ApprovalListApiTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalListApiTest {

    static final String PASSWORD = "test-approvals-pass-1234";
    static final String URL = "/api/v1/approvals";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 계약서 5.5 P1 items 한 줄 + was_assignee·available_actions. */
    static final Set<String> ITEM_KEYS = Set.of("draft_no", "case_no", "assignee", "submitted_at", "verification",
            "last_rejection", "case_version", "was_assignee", "available_actions");
    static final String SECONDS_Z = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SaveStrDraft saveDraft;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired ReviseStrDraft reviseDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired ApprovalRepository approvals;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // --- 응답 모양 ----------------------------------------------------------------

    // 1
    @Test
    void 정상_승인자는_200이고_한_줄의_키가_계약서_P1과_같고_시각은_초_Z_처음_요청은_last_rejection_null() throws Exception {
        long draftId = pendingDraft();

        MvcResult r = call(session("apr01"), "size", "100");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(keys(body)).containsExactlyInAnyOrder("items", "page", "size", "total");
        ObjectNode item = item(body, draftId);
        assertThat(keys(item)).isEqualTo(ITEM_KEYS);
        assertThat(keys(item.path("assignee"))).containsExactlyInAnyOrder("login_id", "display_name");
        assertThat(keys(item.path("verification")))
                .containsExactlyInAnyOrder("verification_id", "revision_no", "status", "passed");

        assertThat(item.path("draft_no").asText()).isEqualTo(DisplayNumber.draftNo(draftId));
        assertThat(item.path("case_no").asText()).matches("CASE-\\d{4}-\\d{6}");
        assertThat(item.path("assignee").path("login_id").asText()).isEqualTo("inv01");
        assertThat(item.path("submitted_at").asText()).matches(SECONDS_Z);
        assertThat(item.path("verification").path("status").asText()).isEqualTo("PASSED");
        assertThat(item.path("verification").path("passed").asBoolean()).isTrue();
        assertThat(item.path("verification").path("revision_no").asInt()).isEqualTo(1);
        assertThat(item.path("last_rejection").isNull()).isTrue();     // 키는 있고 값이 null (계약서 2.3)
        assertThat(item.path("case_version").isIntegralNumber()).isTrue();
        assertThat(item.path("was_assignee").asBoolean()).isFalse();
        assertThat(item.path("available_actions")).isEqualTo(om.readTree("[\"APPROVE\",\"REJECT\"]"));
    }

    // 2
    @Test
    void 정상_보완_요청_뒤_다시_요청한_줄은_last_rejection_키_셋과_decided_at_초_Z() throws Exception {
        long draftId = resubmittedAfterRevision("근거 거래 설명 보완");

        ObjectNode item = item(json(call(session("apr01"), "size", "100")), draftId);

        JsonNode rejection = item.path("last_rejection");
        assertThat(keys(rejection)).containsExactlyInAnyOrder("reason_code", "comment", "decided_at");
        assertThat(rejection.path("reason_code").asText()).isEqualTo("NEED_REVISION");
        assertThat(rejection.path("comment").asText()).isEqualTo("근거 거래 설명 보완");
        assertThat(rejection.path("decided_at").asText()).matches(SECONDS_Z);
    }

    // --- 권한 -------------------------------------------------------------------

    // 3
    @Test
    void 정상_관리자는_200이고_버튼이_없다() throws Exception {
        long draftId = pendingDraft();

        ObjectNode item = item(json(call(session("admin"), "size", "100")), draftId);

        assertThat(item.path("available_actions")).isEqualTo(om.readTree("[]"));
        assertThat(item.path("was_assignee").asBoolean()).isFalse();
    }

    // 4
    @Test
    void 실패_조사역은_403_FORBIDDEN() throws Exception {
        MvcResult r = call(session("inv01"));

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("FORBIDDEN");
    }

    // 5
    @Test
    void 실패_비로그인은_401_UNAUTHENTICATED() throws Exception {
        MvcResult r = mvc.perform(get(URL)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
    }

    // --- page·size ----------------------------------------------------------------

    // 6
    @Test
    void 경계_size_100은_200이고_기본값은_page_0_size_20() throws Exception {
        MockHttpSession apr = session("apr01");

        assertThat(call(apr, "size", "100").getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(call(apr));
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
    }

    // 7
    @Test
    void 실패_size_101_0과_page_음수는_400_INVALID_REQUEST이고_details는_그_파라미터() throws Exception {
        MockHttpSession apr = session("apr01");
        for (String[] q : List.of(new String[]{"size", "101"}, new String[]{"size", "0"}, new String[]{"page", "-1"})) {
            MvcResult r = call(apr, q);
            assertThat(r.getResponse().getStatus()).as(q[0] + "=" + q[1]).isEqualTo(400);
            assertThat(code(r)).as(q[0] + "=" + q[1]).isEqualTo("INVALID_REQUEST");
            assertThat(json(r).path("error").path("details").has(q[0]))
                    .as(q[0] + "=" + q[1] + " details=" + json(r).path("error").path("details")).isTrue();
        }
    }

    // --- 도우미: 승인 대기 만들기 -----------------------------------------------------

    /** ESCALATED(담당 inv01) → 초안 저장(DRAFTED) → 검증 통과 한 줄 → 승인 요청(PENDING_APPROVAL). */
    private long pendingDraft() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);
        return draftId;
    }

    /** 위와 같이 만든 뒤 반려 기록(보완 요청)을 남기고, 케이스를 반려 상태로 → 고쳐 저장 → 다시 요청. P3 API 는 다음 PR이라 반려는 저장소로. */
    private long resubmittedAfterRevision(String comment) {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        asUser(userId("apr01"), "APPROVER");
        tx.executeWithoutResult(s -> approvals.save(Approval.rejected(drafts.findById(draftId).orElseThrow(),
                RejectionReasonCode.NEED_REVISION, comment)));
        SecurityContextHolder.clearContext();
        jdbc.update("update ops.cases set status = 'REJECTED', rejection_reason_code = 'NEED_REVISION', "
                + "version = version + 1 where id = ?", caseId);
        asUser(userId("inv01"), "INVESTIGATOR");
        reviseDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), Map.of(StrSection.METHOD, "고친 방법"));
        SecurityContextHolder.clearContext();
        submit(caseId, draftId);
        return draftId;
    }

    private void submit(long caseId, long draftId) {
        record Body(String hash, int revisionNo) {}
        Body body = tx.execute(s -> {
            var d = drafts.findById(draftId).orElseThrow();
            return new Body(NarrativeHash.of(d.currentBody()), d.getCurrentRevision().getRevisionNo());
        });
        long inv = userId("inv01");
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, ?, ?, 'PASSED', 'verify_v1', clock_timestamp(), '{}'::jsonb, ?)",
                verificationId, draftId, "g-" + draftId, body.revisionNo(), body.hash(), inv);
        asUser(inv, "INVESTIGATOR");
        submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId, List.of("VI-1"), 3);
        SecurityContextHolder.clearContext();
    }

    private long savedDraft(long caseId) {
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", DisplayNumber.draftNo(draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        asUser(userId("inv01"), "INVESTIGATOR");
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

    /** 경보 + ESCALATED 케이스(담당 inv01). 엔티티 대신 SQL — CaseApiTest 와 같은 틀. */
    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-07-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        long ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id", Long.class, "R_APL_" + n);
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date) values (?, ?, ?, ?, "
                + "'2024-07-01 00:00', '2024-07-02 00:00', '2024-07-02 00:00', '{}'::jsonb, '2024-07-01') returning id",
                Long.class, "ALT-20240701-%06d".formatted(870_000 + n), batchId, ruleId, "ACC-APL-" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-%06d".formatted(870_000 + n), alertId, "ACC-APL-" + n, userId("inv01"));
    }

    private long userId(String loginId) {
        return jdbc.queryForObject("select id from ops.users where login_id = ?", Long.class, loginId);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    /**
     * 유스케이스를 직접 부를 때만 쓰는 로그인 (MockMvc 요청은 아래 session()).
     * 컨텍스트를 새로 만들어 넣는다 — getContext().setAuthentication() 으로 고치면 TestSecurityContextHolder 가 같은 객체를
     * 들고 있어서 clearContext() 뒤에도 남고, MockMvc 요청이 세션 대신 이 사용자로 돈다(승인자 요청이 조사역 403).
     */
    private static void asUser(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
    }

    // --- 도우미: 요청·응답 -------------------------------------------------------------

    /** 이 테스트가 만든 초안의 줄 — 같은 DB 를 다른 테스트와 같이 쓰므로 draft_no 로 고른다. */
    private static ObjectNode item(JsonNode body, long draftId) {
        String draftNo = DisplayNumber.draftNo(draftId);
        for (JsonNode i : body.path("items")) {
            if (draftNo.equals(i.path("draft_no").asText())) return (ObjectNode) i;
        }
        throw new AssertionError(draftNo + " 줄이 없다: " + body);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private MvcResult call(MockHttpSession session, String... nameValues) throws Exception {
        var req = get(URL).session(session);
        for (int i = 0; i < nameValues.length; i += 2) req.param(nameValues[i], nameValues[i + 1]);
        return mvc.perform(req).andReturn();
    }

    private MockHttpSession session(String loginId) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", PASSWORD)))).andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + loginId).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }
}
