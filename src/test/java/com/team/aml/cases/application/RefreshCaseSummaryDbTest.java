package com.team.aml.cases.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CaseSummary;
import com.team.aml.cases.domain.CaseSummaryRepository;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.CaseSummaryRequest;
import com.team.aml.shared.ai.AiDtos.CaseSummaryResponse;
import com.team.aml.shared.ai.AiDtos.StrDraftResponse;
import com.team.aml.shared.security.AppUserDetails;
import jakarta.persistence.OptimisticLockException;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RefreshCaseSummary (CASES 명세 API-12, 규약 v0.4 §6.1) — 실제 PostgreSQL. AI 는 @MockBean 으로 테스트마다 응답을 정한다.
 * 경보·거래·점수는 detection GetAlertForDraft 가 읽는 그대로 만든다(GetAlertForDraftTest 와 같은 도우미).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RefreshCaseSummaryDbTest {

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
    @Autowired RefreshCaseSummary refreshSummary;
    @Autowired CaseRepository cases;
    @Autowired CaseHistoryRepository histories;
    @Autowired CaseSummaryRepository summaries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    @Autowired PlatformTransactionManager transactionManager;

    private long inv;
    private long inv2;

    @BeforeEach
    void setUp() {
        inv = user("s_inv01", "INVESTIGATOR");
        inv2 = user("s_inv02", "INVESTIGATOR");
        reset(aiClient);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    @Test
    void 정상_규약_6_1_요청을_보내고_요약_한_행을_저장하며_version이_1_오른다() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        ArgumentCaptor<CaseSummaryRequest> sent = ArgumentCaptor.forClass(CaseSummaryRequest.class);
        when(aiClient.caseSummary(sent.capture())).thenAnswer(i -> ok(i.getArgument(0)));
        int historyCount = histories.findByCaseIdOrderByIdAsc(f.c.getId()).size();
        loginAs(inv);

        CaseSummaryResult result = refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion());

        // 보낸 요청 — 초안 요청과 같은 값(경보 원문·reasons·prior_alert_count), 메모 없음
        CaseSummaryRequest req = sent.getValue();
        assertThat(req.caseNo()).isEqualTo(f.c.getCaseNo());
        assertThat(req.alert().alertId()).isEqualTo(f.alertNo);
        assertThat(req.evidenceTransactionIds()).containsExactly(f.tx1, f.tx2);   // 오름차순
        assertThat(req.reasons().get(0).get("name").asText()).isEqualTo("account_in_counterparties_24h");
        assertThat(req.priorAlertCount()).isEqualTo(1);
        assertThat(req.reportingInstitution()).isNull();

        // 응답 — 화면용, 저장 뒤 version
        assertThat(result.caseNo()).isEqualTo(f.c.getCaseNo());
        assertThat(result.caseVersion()).isEqualTo(f.c.getVersion() + 1).isEqualTo(dbVersion(f.c.getId()));
        assertThat(result.summary().summaryLines()).containsExactly("첫 줄", "둘째 줄");
        assertThat(result.summary().modelFocus()).isEqualTo("모델은 inflow_outflow_ratio_2h 신호를 가장 크게 봄");
        assertThat(result.summary().isCurrent()).isTrue();
        assertThat(result.summary().basedOnCaseVersion()).isEqualTo(f.c.getVersion());
        assertThat(result.summary().generatorVersion()).isEqualTo("summary_v1");
        assertThat(result.summary().inputSha256()).matches("[0-9a-f]{64}");

        // 저장 — 응답 원문, 실제 보낸 입력, 해시, 갱신한 사람. 이력 행은 없다
        CaseSummary row = summaries.findFirstByCaseIdOrderByCreatedAtDescIdDesc(f.c.getId()).orElseThrow();
        assertThat(row.getPayload().path("generation_id").asText()).isEqualTo("GEN-1");
        assertThat(row.getInputSnapshot().fieldNames()).toIterable().containsExactlyInAnyOrder("case_no", "alert",
                "evidence_transaction_ids", "reasons", "prior_alert_count", "reporting_institution");
        assertThat(row.getInputSha256()).isEqualTo(result.summary().inputSha256());
        assertThat(row.getProducedAt()).hasToString("2026-11-06T09:00:00Z");
        assertThat(row.getBasedOnCaseVersion()).isEqualTo(f.c.getVersion());
        assertThat(row.getCreatedBy()).isEqualTo(inv);
        assertThat(histories.findByCaseIdOrderByIdAsc(f.c.getId())).hasSize(historyCount);
    }

    @Test
    void 정상_점수가_아직_없으면_reasons는_빈_배열로_보낸다() {   // AI 는 null 을 422 로 거절 (#64 리뷰)
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, false);
        ArgumentCaptor<CaseSummaryRequest> sent = ArgumentCaptor.forClass(CaseSummaryRequest.class);
        when(aiClient.caseSummary(sent.capture())).thenAnswer(i -> ok(i.getArgument(0)));
        loginAs(inv);

        refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion());

        assertThat(sent.getValue().reasons().isArray()).isTrue();
        assertThat(sent.getValue().reasons()).isEmpty();
    }

    @Test
    void 경계_model_focus가_null이면_화면_칸도_null() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        when(aiClient.caseSummary(any())).thenAnswer(i -> response(i.getArgument(0), raw -> raw.putNull("model_focus")));
        loginAs(inv);

        CaseSummaryResult result = refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion());

        assertThat(result.summary().modelFocus()).isNull();
        assertThat(result.summary().summaryLines()).containsExactly("첫 줄", "둘째 줄");
    }

    @Test
    void 정상_입력이_같으면_다시_만들어도_해시가_같고_새_행이_현재_요약() {
        Fixture f = caseWithAlert(CaseStatus.ESCALATED, true);
        when(aiClient.caseSummary(any())).thenAnswer(i -> ok(i.getArgument(0)));
        loginAs(inv);

        CaseSummaryResult first = refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion());
        CaseSummaryResult second = refreshSummary.execute(f.c.getCaseNo(), first.caseVersion());

        assertThat(second.summary().inputSha256()).isEqualTo(first.summary().inputSha256());
        assertThat(second.caseVersion()).isEqualTo(first.caseVersion() + 1);
        assertThat(second.summary().basedOnCaseVersion()).isEqualTo(first.caseVersion());
        assertThat(jdbc.queryForObject("select count(*) from ops.case_summaries where case_id = ?", Long.class,
                f.c.getId())).isEqualTo(2L);
        assertThat(summaries.findFirstByCaseIdOrderByCreatedAtDescIdDesc(f.c.getId()).orElseThrow()
                .getBasedOnCaseVersion()).isEqualTo(first.caseVersion());
    }

    @Test
    void 경계_AI를_기다리는_동안에는_트랜잭션이_없다() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        AtomicBoolean inTx = new AtomicBoolean(true);
        when(aiClient.caseSummary(any())).thenAnswer(i -> {
            inTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return ok(i.getArgument(0));
        });
        loginAs(inv);

        refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion());

        assertThat(inTx).isFalse();
    }

    // ── 호출 전 거부 (AI 를 부르지 않는다) ───────────────────────

    @Test
    void 실패_없는_케이스는_404() {
        loginAs(inv);
        assertThatThrownBy(() -> refreshSummary.execute("CASE-2022-999999", 0))
                .isInstanceOf(CaseNotFoundException.class);
        verify(aiClient, never()).caseSummary(any());
    }

    @Test
    void 실패_화면이_읽은_version이_다르면_409() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        loginAs(inv);
        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion() - 1))
                .isInstanceOf(OptimisticLockException.class);
        verify(aiClient, never()).caseSummary(any());
    }

    @Test
    void 실패_담당이_아니면_403() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        loginAs(inv2);
        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOf(NotAssigneeException.class);
        verify(aiClient, never()).caseSummary(any());
    }

    @Test
    void 실패_편집할_수_없는_상태면_409_action_REFRESH_SUMMARY() {
        Fixture f = caseWithAlert(CaseStatus.CLOSED, true);
        loginAs(inv);
        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> assertThat(e.details())
                        .containsEntry("action", "REFRESH_SUMMARY").containsEntry("from_status", CaseStatus.CLOSED));
        verify(aiClient, never()).caseSummary(any());
    }

    // ── AI 실패·응답 거부 (기존 요약 유지) ───────────────────────

    @Test
    void 실패_AI_오류는_그대로_올리고_아무것도_저장하지_않는다() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        when(aiClient.caseSummary(any())).thenThrow(new AiClientException("LLM_UNAVAILABLE", "down"));
        loginAs(inv);

        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("LLM_UNAVAILABLE"));
        assertNothingSaved(f);
    }

    @Test
    void 실패_코드_검사가_하나라도_실패면_저장하지_않고_502_SUMMARY_CODE_CHECK_FAILED() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        when(aiClient.caseSummary(any())).thenAnswer(i -> response(i.getArgument(0), raw -> {
            ArrayNode checks = raw.putArray("code_checks");
            checks.addObject().put("check", "COUNT_MATCH").put("match", true);
            checks.addObject().put("check", "AMOUNT_MATCH").put("match", false).put("reason", "MISMATCH");
            return raw;
        }));
        loginAs(inv);

        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    assertThat(e.code()).isEqualTo("SUMMARY_CODE_CHECK_FAILED");
                    assertThat(e.httpStatus()).isEqualTo(502);
                    assertThat(e.details()).containsEntry("failed_checks", List.of("AMOUNT_MATCH"));
                });
        assertNothingSaved(f);
    }

    @Test
    void 실패_응답_case_no가_다르거나_필수_칸이_없으면_502_AI_BAD_RESPONSE() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        loginAs(inv);
        List<Function<ObjectNode, ObjectNode>> broken = List.of(
                raw -> raw.put("case_no", "CASE-2022-000000"),
                raw -> { raw.remove("produced_at"); return raw; },
                raw -> { raw.putObject("generation_meta"); return raw; },
                raw -> { raw.remove("code_checks"); return raw; },
                raw -> { raw.putArray("code_checks"); return raw; },   // 빈 code_checks 는 "전부 match" 가 아니다 (#65 리뷰)
                raw -> { ArrayNode lines = raw.putArray("summary_lines"); for (int n = 0; n < 7; n++) lines.add("줄"); return raw; });

        for (Function<ObjectNode, ObjectNode> edit : broken) {
            reset(aiClient);
            when(aiClient.caseSummary(any())).thenAnswer(i -> response(i.getArgument(0), edit));
            assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                    .isInstanceOfSatisfying(AiClientException.class, e -> assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE"));
        }
        assertNothingSaved(f);
    }

    // ── AI 대기 중 바뀐 경우 (늦게 온 응답은 저장하지 않는다) ─────────

    @Test
    void 실패_AI를_기다리는_사이_케이스가_종결되면_409이고_요약을_저장하지_않는다() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, true);
        when(aiClient.caseSummary(any())).thenAnswer(i -> {
            tx().executeWithoutResult(s -> {
                Case c = cases.findById(f.c.getId()).orElseThrow();
                c.close(inv, CloseReason.NO_ACTION, "오탐");
                Case saved = cases.saveAndFlush(c);
                histories.saveAll(saved.takePendingHistories());
            });
            return ok(i.getArgument(0));
        });
        loginAs(inv);

        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(summaryCount(f.c.getId())).isZero();
        assertThat(cases.findById(f.c.getId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.CLOSED);
    }

    @Test
    void 실패_AI를_기다리는_사이_요약_입력이_바뀌면_409이고_저장하지_않는다() {
        Fixture f = caseWithAlert(CaseStatus.REVIEWING, false);   // 점수 없음 → reasons []
        when(aiClient.caseSummary(any())).thenAnswer(i -> {
            score(f.alertId);                                    // 그사이 점수가 들어와 reasons 가 바뀜 (case version 은 그대로)
            return ok(i.getArgument(0));
        });
        loginAs(inv);

        assertThatThrownBy(() -> refreshSummary.execute(f.c.getCaseNo(), f.c.getVersion()))
                .isInstanceOf(OptimisticLockException.class);
        assertNothingSaved(f);
    }

    // ── 도우미 ────────────────────────────────────────────────

    private record Fixture(Case c, long alertId, String alertNo, long tx1, long tx2) {}

    /** 경보(거래 2건, 같은 법인의 앞선 경보 1건, withScore 면 reasons 있는 점수) + target 상태 케이스. */
    private Fixture caseWithAlert(CaseStatus target, boolean withScore) {
        long n = SEQ.incrementAndGet();
        String entity = "E_SUM_" + n;
        long rule = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, '테스트 룰', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_SUM_" + n);
        alert(rule, entity, "2022-09-01 12:00:00", n * 10 + 1);                 // 앞선 경보 → prior_alert_count 1
        String alertNo = "ALT-SUM-" + (n * 10);
        long alertId = alert(rule, entity, "2022-09-02 23:59:59", n * 10);
        long tx2 = transaction();
        long tx1 = transaction();
        link(alertId, tx2);
        link(alertId, tx1);
        if (withScore) {
            score(alertId);
        }
        Long id = tx().execute(s -> {
            Case c = Case.open("CASE-2022-%06d".formatted(800000 + n), alertId, "10_S" + n, entity, SYSTEM);
            c.assign(inv, inv);
            if (target == CaseStatus.ESCALATED) {
                c.escalate(inv, "STR 초안 작성 필요");
            }
            if (target == CaseStatus.CLOSED) {
                c.close(inv, CloseReason.NO_ACTION, "오탐");
            }
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
                "ALT-SUM-" + n, batchRunId, ruleRefId, "10_S" + n, entityId, referenceTs, referenceTs, referenceTs);
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
                "insert into ops.source_files(file_name, file_hash) values ('s.csv', ?) returning id",
                Long.class, String.format("%064d", 900000 + n));
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

    /** 코드 검사를 전부 통과한 규약 §6.1 응답. */
    private CaseSummaryResponse ok(CaseSummaryRequest req) {
        return response(req, raw -> raw);
    }

    private CaseSummaryResponse response(CaseSummaryRequest req, Function<ObjectNode, ObjectNode> edit) {
        ObjectNode raw = om.createObjectNode()
                .put("produced_at", "2026-11-06T09:00:00Z")
                .put("case_no", req.caseNo())
                .put("generation_id", "GEN-1");
        raw.putObject("facts").putObject("inbound").put("count", 2);
        raw.putArray("summary_lines").add("첫 줄").add("둘째 줄");
        raw.put("model_focus", "모델은 inflow_outflow_ratio_2h 신호를 가장 크게 봄");
        raw.putArray("code_checks").addObject().put("check", "COUNT_MATCH").put("match", true);
        raw.putObject("generation_meta").put("prompt_version", "summary_v1");
        raw = edit.apply(raw);
        List<String> lines = raw.has("summary_lines")
                ? om.convertValue(raw.get("summary_lines"), om.getTypeFactory().constructCollectionType(List.class, String.class))
                : null;
        JsonNode meta = raw.path("generation_meta");
        return new CaseSummaryResponse(raw.path("produced_at").isMissingNode() ? null : raw.get("produced_at").asText(),
                raw.path("case_no").asText(null), raw.path("generation_id").asText(null), lines, raw.get("code_checks"),
                new StrDraftResponse.GenerationMetaView(meta.hasNonNull("prompt_version") ? meta.get("prompt_version").asText() : null),
                raw);
    }

    private void assertNothingSaved(Fixture f) {
        assertThat(summaryCount(f.c.getId())).isZero();
        assertThat(dbVersion(f.c.getId())).isEqualTo(f.c.getVersion());
    }

    private long summaryCount(long caseId) {
        return jdbc.queryForObject("select count(*) from ops.case_summaries where case_id = ?", Long.class, caseId);
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
