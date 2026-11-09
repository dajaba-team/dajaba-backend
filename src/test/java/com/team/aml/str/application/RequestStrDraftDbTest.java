package com.team.aml.str.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.StrDraftRequest;
import com.team.aml.shared.ai.AiDtos.StrDraftResponse;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftInProgressException;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RequestStrDraft (계약서 S1, 규약 v0.4 §5.1) — 실제 PostgreSQL. AI 는 @MockBean 으로 규약 §5.2 모양(7항목, cites)을 돌려준다.
 * 경보·거래·점수는 detection GetAlertForDraft 가 읽는 그대로 만든다(RefreshCaseSummaryDbTest 와 같은 도우미).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RequestStrDraftDbTest {

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

    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @MockBean AiClient aiClient;
    @Autowired RequestStrDraft requestDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    @Autowired PlatformTransactionManager transactionManager;

    private long inv;
    private long inv2;

    @BeforeEach
    void setUp() {
        inv = user("d_inv01", "INVESTIGATOR");
        inv2 = user("d_inv02", "INVESTIGATOR");
        reset(aiClient);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_규약_5_1_요청을_보내고_초안을_저장해_케이스가_DRAFTED가_된다() {
        Fixture f = escalatedCase(true);
        ArgumentCaptor<StrDraftRequest> sent = ArgumentCaptor.forClass(StrDraftRequest.class);
        when(aiClient.createDraft(sent.capture())).thenAnswer(i -> ok(i.getArgument(0)));
        loginAs(inv);

        RequestStrDraft.Created created = requestDraft.execute(f.c.getCaseNo(), f.c.getVersion());

        // 보낸 요청 — 규약 §5.1
        StrDraftRequest req = sent.getValue();
        assertThat(req.draftId()).isEqualTo(created.draftNo()).matches("DRF-\\d{6,}");
        assertThat(req.caseNo()).isEqualTo(f.c.getCaseNo());
        assertThat(req.alert().alertId()).isEqualTo(f.alertNo);
        assertThat(req.evidenceTransactionIds()).containsExactly(f.tx1, f.tx2);   // 중복 없이 오름차순
        assertThat(req.reasons().get(0).get("name").asText()).isEqualTo("account_in_counterparties_24h");
        assertThat(req.priorAlertCount()).isEqualTo(1);
        assertThat(req.investigatorNotes()).isNull();
        assertThat(req.reportingInstitution()).isNull();

        // 돌려준 값 — 201 응답
        assertThat(created.caseNo()).isEqualTo(f.c.getCaseNo());
        assertThat(created.caseStatus()).isEqualTo("DRAFTED");
        assertThat(created.caseVersion()).isEqualTo(dbVersion(f.c.getId())).isGreaterThan(f.c.getVersion());

        // 저장 — 요청 기록 SUCCEEDED(보낸 본문 그대로), 초안 ACTIVE, 케이스 DRAFTED·current_draft_id
        long draftId = draftId(created.draftNo());
        assertThat(jdbc.queryForObject("select status from ops.str_draft_requests where draft_id = ?", String.class, draftId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select request_snapshot ->> 'draft_id' from ops.str_draft_requests where draft_id = ?",
                String.class, draftId)).isEqualTo(created.draftNo());
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class, draftId))
                .isEqualTo("ACTIVE");
        Case after = cases.findById(f.c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.DRAFTED);
        assertThat(after.getCurrentDraftId()).isEqualTo(draftId);
    }

    // 2
    @Test
    void 정상_점수가_아직_없으면_reasons는_빈_배열로_보낸다() {   // AI 는 null 을 422 로 거절 (#64 리뷰)
        Fixture f = escalatedCase(false);
        ArgumentCaptor<StrDraftRequest> sent = ArgumentCaptor.forClass(StrDraftRequest.class);
        when(aiClient.createDraft(sent.capture())).thenAnswer(i -> ok(i.getArgument(0)));
        loginAs(inv);

        requestDraft.execute(f.c.getCaseNo(), f.c.getVersion());

        assertThat(sent.getValue().reasons().isArray()).isTrue();
        assertThat(sent.getValue().reasons()).isEmpty();
    }

    // 3
    @Test
    void 정상_다시_만들면_새_draft_id로_요청하고_예전_초안은_SUPERSEDED() {
        Fixture f = escalatedCase(true);
        when(aiClient.createDraft(any())).thenAnswer(i -> ok(i.getArgument(0)));
        loginAs(inv);

        RequestStrDraft.Created first = requestDraft.execute(f.c.getCaseNo(), f.c.getVersion());
        RequestStrDraft.Created second = requestDraft.execute(f.c.getCaseNo(), first.caseVersion());

        assertThat(second.draftNo()).isNotEqualTo(first.draftNo());
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class,
                draftId(first.draftNo()))).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class,
                draftId(second.draftNo()))).isEqualTo("ACTIVE");
        assertThat(cases.findById(f.c.getId()).orElseThrow().getCurrentDraftId()).isEqualTo(draftId(second.draftNo()));
    }

    // ── 실패 ──────────────────────────────────────────────────

    // 4
    @Test
    void 실패_AI_오류면_요청_기록은_FAILED이고_케이스는_ESCALATED_그대로() {
        Fixture f = escalatedCase(true);
        when(aiClient.createDraft(any())).thenThrow(new AiClientException("LLM_UNAVAILABLE", "LLM 호출 실패"));
        loginAs(inv);

        assertThatThrownBy(() -> requestDraft.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("LLM_UNAVAILABLE"));

        assertThat(jdbc.queryForList("select status from ops.str_draft_requests where case_id = ?", String.class,
                f.c.getId())).containsExactly("FAILED");
        assertThat(draftCount(f.c.getId())).isZero();
        Case after = cases.findById(f.c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(after.getVersion()).isEqualTo(f.c.getVersion());
    }

    // 5
    @Test
    void 실패_만드는_중인_요청이_있으면_409_DRAFT_IN_PROGRESS이고_AI를_부르지_않는다() {
        Fixture f = escalatedCase(true);
        loginAs(inv);
        tx().executeWithoutResult(s -> requests.save(com.team.aml.str.domain.StrDraftRequest.request(
                requests.nextDraftId(), f.c.getId(), om.createObjectNode())));

        assertThatThrownBy(() -> requestDraft.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOf(DraftInProgressException.class);

        verify(aiClient, never()).createDraft(any());
    }

    // 6
    @Test
    void 실패_담당이_아니면_403이고_요청_기록도_남기지_않는다() {
        Fixture f = escalatedCase(true);
        loginAs(inv2);

        assertThatThrownBy(() -> requestDraft.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOf(NotAssigneeException.class);

        verify(aiClient, never()).createDraft(any());
        assertThat(jdbc.queryForObject("select count(*) from ops.str_draft_requests where case_id = ?", Long.class,
                f.c.getId())).isZero();
    }

    // ── 도우미 ────────────────────────────────────────────────

    private record Fixture(Case c, long alertId, String alertNo, long tx1, long tx2) {}

    /** 경보(거래 2건, 같은 법인의 앞선 경보 1건, withScore 면 reasons 있는 점수) + inv 담당 ESCALATED 케이스. */
    private Fixture escalatedCase(boolean withScore) {
        long n = SEQ.incrementAndGet();
        String entity = "E_DRF_" + n;
        long rule = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, '테스트 룰', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_DRF_" + n);
        alert(rule, entity, "2022-09-01 12:00:00", n * 10 + 1);                 // 앞선 경보 → prior_alert_count 1
        String alertNo = "ALT-DRF-" + (n * 10);
        long alertId = alert(rule, entity, "2022-09-02 23:59:59", n * 10);
        long tx2 = transaction();
        long tx1 = transaction();
        link(alertId, tx2);
        link(alertId, tx1);
        if (withScore) {
            score(alertId);
        }
        Long id = tx().execute(s -> {
            Case c = Case.open("CASE-2022-%06d".formatted(700000 + n), alertId, "10_D" + n, entity, SYSTEM);
            c.assign(inv, inv);
            c.escalate(inv, "STR 초안 작성 필요");
            Case saved = cases.save(c);
            histories.saveAll(saved.takePendingHistories());
            return saved.getId();
        });
        return new Fixture(cases.findById(id).orElseThrow(), alertId, alertNo, Math.min(tx1, tx2), Math.max(tx1, tx2));
    }

    private long alert(long ruleRefId, String entityId, String referenceTs, long n) {
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2022-09-02', 'RUNNING', 1) returning id""", Long.class);
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, ?, ?::timestamp - interval '1 day', ?::timestamp, ?::timestamp,
                        '{"inbound_count": 2}'::jsonb, '2022-09-02') returning id""", Long.class,
                "ALT-DRF-" + n, batchRunId, ruleRefId, "10_D" + n, entityId, referenceTs, referenceTs, referenceTs);
    }

    private void score(long alertId) {
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, 0.9, 0.7, 'xgb-test', 'f1', ?, now(),
                        '{"reasons": [{"name": "account_in_counterparties_24h", "observation": {"value": 3}}]}'::jsonb)""",
                alertId, "p-" + alertId);
    }

    private long transaction() {
        long n = SEQ.incrementAndGet();
        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('d.csv', ?) returning id",
                Long.class, String.format("%064d", 700000 + n));
        return jdbc.queryForObject("""
                insert into ops.transactions(ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values ('2022-09-02 10:00', '10', 'A', '10_A', '20', 'B', '20_B', 1, 'US Dollar', 1, 'US Dollar',
                        'ACH', false, false, ?, 1, '010', '020') returning transaction_id""", Long.class, fileId);
    }

    private void link(long alertId, long transactionId) {
        jdbc.update("insert into ops.alert_transactions(alert_id, transaction_id) values (?, ?)", alertId, transactionId);
    }

    /** 규약 §5.2 모양의 7항목 응답. draft_id 는 받은 요청 그대로(AI 도 그렇게 한다). */
    private StrDraftResponse ok(StrDraftRequest req) {
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", "2026-11-09T01:10:00Z")
                .put("draft_id", req.draftId())
                .put("generation_id", "GEN-" + req.draftId());
        raw.putObject("facts").putObject("inbound").put("count", 2);
        raw.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = raw.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(req.evidenceTransactionIds().get(0));
        }
        raw.putNull("verification");
        return new StrDraftResponse(raw.get("produced_at").asText(), req.draftId(), raw.get("generation_id").asText(),
                null, new StrDraftResponse.GenerationMetaView("narrative_v2"), raw.get("facts"), null, raw);
    }

    private long draftId(String draftNo) {
        return Long.parseLong(draftNo.substring("DRF-".length()));
    }

    private long draftCount(long caseId) {
        return jdbc.queryForObject("select count(*) from ops.str_drafts where case_id = ?", Long.class, caseId);
    }

    private long dbVersion(long caseId) {
        return cases.findById(caseId).orElseThrow().getVersion();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void loginAs(long userId) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, "INVESTIGATOR");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1)
                on conflict (login_id) do update set display_name = excluded.display_name
                returning id
                """, Long.class, loginId, loginId, role);
    }
}
