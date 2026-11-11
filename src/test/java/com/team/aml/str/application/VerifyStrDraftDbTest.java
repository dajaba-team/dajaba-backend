package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import com.team.aml.str.domain.VerifyInProgressException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VerifyStrDraft (S5) — 실제 PostgreSQL + AiClient 목. 초안은 SaveStrDraft 로 만든다(GetStrDraftDbTest 와 같은 재료).
 * 저장본을 보내고(cites 포함), 결과는 상태와 상관없이 verification_results 한 줄. AI 실패·권한 실패는 아무것도 남기지 않는다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class VerifyStrDraftDbTest {

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

    @MockBean AiClient aiClient;
    @Autowired VerifyStrDraft verifyDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long otherInvestigator;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("ver-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("ver-inv-other" + n, "INVESTIGATOR");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-01-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 700 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_저장본을_cites까지_보내고_PASSED_결과_1행이_수정본_번호_해시와_함께_남는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        when(aiClient.verify(anyString(), any())).thenAnswer(i -> answer(i.getArgument(1), "PASSED"));
        login(investigator, "INVESTIGATOR");

        JsonNode body = verifyDraft.execute(DisplayNumber.draftNo(draftId));

        ArgumentCaptor<AiDtos.VerifyRequest> sent = ArgumentCaptor.forClass(AiDtos.VerifyRequest.class);
        verify(aiClient).verify(org.mockito.ArgumentMatchers.eq(DisplayNumber.draftNo(draftId)), sent.capture());
        assertThat(sent.getValue().generationId()).isEqualTo("g-" + draftId);
        assertThat(sent.getValue().revisionNo()).isEqualTo(1);
        assertThat(sent.getValue().narrative()).hasSize(StrSection.V2.size());
        assertThat(sent.getValue().narrative().get(0).cites()).containsExactly("period", "inbound.count");

        assertThat(body.path("status").asText()).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("select status || ',' || passed || ',' || revision_no || ',' || generation_id "
                + "|| ',' || created_by from ops.verification_results where draft_id = ?", String.class, draftId))
                .isEqualTo("PASSED,true,1,g-" + draftId + "," + investigator);
        assertThat(jdbc.queryForObject("select narrative_sha256 from ops.verification_results where draft_id = ?",
                String.class, draftId)).isEqualTo(hashOf(sent.getValue()));
    }

    // 2
    @Test
    void 정상_VERIFY_UNAVAILABLE도_저장하고_passed는_false다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        when(aiClient.verify(anyString(), any())).thenAnswer(i -> answer(i.getArgument(1), "VERIFY_UNAVAILABLE"));
        login(investigator, "INVESTIGATOR");

        JsonNode body = verifyDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(body.path("status").asText()).isEqualTo("VERIFY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select passed from ops.verification_results where draft_id = ?",
                Boolean.class, draftId)).isFalse();
        verify(aiClient).verify(anyString(), any());   // 자동 재시도 없음 — 한 번만
    }

    // 2-2
    @Test
    void 정상_다시_검증하면_앞_검증의_narrative_refs_근거를_보낸다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        when(aiClient.verify(anyString(), any())).thenAnswer(i -> {
            AiDtos.VerifyResponse res = answer(i.getArgument(1), "PASSED");
            ((ObjectNode) res.raw()).putArray("narrative_refs").addObject().put("section", "METHOD")
                    .<ObjectNode>set("cites", JSON.createArrayNode().add("counterparties.count"))
                    .set("evidence_ids", JSON.createArrayNode().add(104233));
            return res;
        });
        login(investigator, "INVESTIGATOR");

        verifyDraft.execute(DisplayNumber.draftNo(draftId));
        verifyDraft.execute(DisplayNumber.draftNo(draftId));

        ArgumentCaptor<AiDtos.VerifyRequest> sent = ArgumentCaptor.forClass(AiDtos.VerifyRequest.class);
        verify(aiClient, org.mockito.Mockito.times(2)).verify(anyString(), sent.capture());
        AiDtos.NarrativeSectionDto first = method(sent.getAllValues().get(0));
        AiDtos.NarrativeSectionDto second = method(sent.getAllValues().get(1));
        assertThat(first.cites()).containsExactly("period", "inbound.count");
        assertThat(second.cites()).containsExactly("counterparties.count");
        assertThat(second.evidenceIds()).containsExactly(104233L);
        assertThat(second.text()).isEqualTo(first.text());
    }

    // ── 실패 ──────────────────────────────────────────────────

    // 3
    @Test
    void 실패_AI_오류면_502_예외이고_아무것도_저장하지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        when(aiClient.verify(anyString(), any())).thenThrow(new AiClientException("LLM_TIMEOUT", "timeout"));
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> verifyDraft.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(AiClientException.class);

        assertThat(resultCount(draftId)).isZero();
    }

    // 4
    @Test
    void 실패_담당이_아닌_조사역은_403이고_AI를_부르지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(otherInvestigator, "INVESTIGATOR");

        assertThatThrownBy(() -> verifyDraft.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(NotAssigneeException.class);

        verify(aiClient, never()).verify(anyString(), any());
    }

    // 5
    @Test
    void 실패_승인_대기_중이면_409_INVALID_CASE_TRANSITION이고_AI를_부르지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("update ops.cases set status = 'PENDING_APPROVAL', version = version + 1 where id = ?", caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> verifyDraft.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(InvalidCaseTransitionException.class);

        verify(aiClient, never()).verify(anyString(), any());
    }

    // 5-2
    @Test
    void 실패_보완_요청_반려_뒤_저장하지_않았으면_409이고_AI를_부르지_않는다() {   // 계약서 4절, #95 리뷰
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("update ops.cases set status = 'REJECTED', rejection_reason_code = 'NEED_REVISION', "
                + "version = version + 1 where id = ?", caseId);
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> verifyDraft.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(InvalidCaseTransitionException.class);

        verify(aiClient, never()).verify(anyString(), any());
        assertThat(resultCount(draftId)).isZero();
    }

    // 6
    @Test
    void 실패_같은_초안을_검증하는_중이면_409_VERIFY_IN_PROGRESS() throws Exception {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiClient.verify(anyString(), any())).thenAnswer(i -> {
            aiStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return answer(i.getArgument(1), "PASSED");
        });
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() -> {
            login(investigator, "INVESTIGATOR");
            try {
                return verifyDraft.execute(DisplayNumber.draftNo(draftId));
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertThat(aiStarted.await(10, TimeUnit.SECONDS)).isTrue();
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> verifyDraft.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(VerifyInProgressException.class);

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).path("status").asText()).isEqualTo("PASSED");
        assertThat(resultCount(draftId)).isEqualTo(1);
    }

    // ---- 도우미 ----

    /** 규약 5.3 모양의 응답. 해시는 받은 본문으로 계산(AI 와 같은 규칙). */
    private static AiDtos.VerifyResponse answer(AiDtos.VerifyRequest req, String status) {
        String hash = hashOf(req);
        String verificationId = "v-" + java.util.UUID.randomUUID();
        ObjectNode raw = JSON.createObjectNode()
                .put("verification_id", verificationId)
                .put("generation_id", req.generationId())
                .put("revision_no", req.revisionNo())
                .put("narrative_sha256", hash)
                .put("passed", "PASSED".equals(status))
                .put("status", status)
                .put("verifier_version", "verify_v1")
                .put("produced_at", "2026-11-10T01:00:00Z");
        raw.putArray("code_checks");
        raw.putNull("llm_checks");
        return new AiDtos.VerifyResponse(verificationId, req.generationId(), req.revisionNo(), hash,
                "PASSED".equals(status), status, "verify_v1", "2026-11-10T01:00:00Z", raw);
    }

    private static AiDtos.NarrativeSectionDto method(AiDtos.VerifyRequest req) {
        return req.narrative().stream().filter(n -> n.section().equals("METHOD")).findFirst().orElseThrow();
    }

    private static String hashOf(AiDtos.VerifyRequest req) {
        return NarrativeHash.of(req.narrative().stream()
                .map(d -> new NarrativeSection(StrSection.valueOf(d.section()), d.text(), d.cites(), d.evidenceIds()))
                .toList());
    }

    private long resultCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.verification_results where draft_id = ?",
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
                Long.class, "ALT-20240101-" + String.format("%06d", 820_000 + n), batchRunId, ruleId, "ACC-G" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 820_000 + n), alertId, "ACC-G" + n, investigator);
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
