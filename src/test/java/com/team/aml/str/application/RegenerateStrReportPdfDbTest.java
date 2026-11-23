package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NotApproverException;
import com.team.aml.str.domain.NotDraftApproverException;
import com.team.aml.str.domain.ReportPdfRetryNotAllowedException;
import com.team.aml.str.domain.ReportPdfStatus;
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
 * RegenerateStrReportPdf (P5 다시 만들기) — 실제 PostgreSQL. 승인·제출은 P2·P4 로 실제로, 실패 상태는 GenerateStrReportPdf.fail 로 만든다.
 * 이 초안을 승인한 사람만, 케이스 REPORTED·PDF FAILED 일 때만, FAILED → PENDING 뒤 같은 스냅샷으로 새 시도 번호 파일.
 * 커밋 뒤 자동 생성은 끄고(generate-after-report=false) 상태를 하나씩 본다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.str.pdf.generate-after-report=false",
        "aml.str.pdf.storage=filesystem",
        "aml.str.pdf.storage-dir=build/str-reports-test/regenerate"})
@ActiveProfiles("test")
class RegenerateStrReportPdfDbTest {

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

    @Autowired RegenerateStrReportPdf regenerate;
    @Autowired GenerateStrReportPdf generate;
    @Autowired ReportStrDraft reportDraft;
    @Autowired ApproveStrDraft approveDraft;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
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
        investigator = user("rg-inv" + n, "INVESTIGATOR");
        approver = user("rg-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-11-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_700 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 정상_실패한_PDF를_승인한_사람이_다시_만들면_PENDING이_되고_새_시도_번호로_READY() {
        long draftId = failedDraft();
        login(approver, "APPROVER");

        RegenerateStrReportPdf.Result r = regenerate.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.pdfStatus()).isEqualTo(ReportPdfStatus.PENDING);
        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "PENDING").containsEntry("pdf_failure_code", null)
                .containsEntry("pdf_attempts", 1);                         // 시도 수는 결과가 날 때 센다
        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.READY);   // 커밋 뒤 트리거가 할 일
        assertThat((String) pdfRow(draftId).get("pdf_storage_key")).endsWith("-2.pdf");   // 지난 시도 파일을 덮지 않는다
        assertThat(pdfRow(draftId)).containsEntry("pdf_attempts", 2);
    }

    // 2
    @Test
    void 실패_FAILED가_아니면_409_INVALID_CASE_TRANSITION_action_REGENERATE_PDF와_지금_상태() {   // 프론트 목업과 같음 (11/17)
        long draftId = reportedDraft();                                    // PENDING
        login(approver, "APPROVER");

        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(ReportPdfRetryNotAllowedException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CASE_TRANSITION");
                    assertThat(e.details()).containsEntry("action", "REGENERATE_PDF")
                            .containsEntry("pdf_status", "PENDING")
                            .containsEntry("draft_no", DisplayNumber.draftNo(draftId));
                });

        generate.execute(draftId);                                         // READY
        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(ReportPdfRetryNotAllowedException.class,
                        e -> assertThat(e.details()).containsEntry("pdf_status", "READY"));
    }

    // 3
    @Test
    void 실패_승인하지_않은_다른_승인자는_403_NOT_DRAFT_APPROVER이고_FAILED_그대로() {   // P4 제출자 범위와 같음
        long draftId = failedDraft();
        login(user("rg-apr-other" + SEQ.incrementAndGet(), "APPROVER"), "APPROVER");

        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(NotDraftApproverException.class);

        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED");
    }

    // 4
    @Test
    void 실패_승인자가_아니면_403_NOT_APPROVER() {
        long draftId = failedDraft();
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(NotApproverException.class);
        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED");
    }

    // 5
    @Test
    void 실패_제출_전이면_409_INVALID_CASE_TRANSITION() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);                              // APPROVED — PDF 상태 없음
        login(approver, "APPROVER");

        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                        e -> assertThat(e.details()).containsEntry("action", "REGENERATE_PDF"));
    }

    // 6
    @Test
    void 실패_없는_초안_번호면_404() {
        login(approver, "APPROVER");

        assertThatThrownBy(() -> regenerate.execute("DRF-999999")).isInstanceOf(DraftNotFoundException.class);
    }

    // 7
    @Test
    void 정상_만드는_도중_서버가_죽어_PENDING이_오래되면_다시_만들_수_있다() {   // 11/20 코드 리뷰 — 갇히지 않게
        long draftId = reportedDraft();                                    // 자동 생성 꺼짐 — 작업이 사라진 것과 같다
        jdbc.update("update ops.str_reports set updated_at = now() - interval '11 minutes' where draft_id = ?", draftId);
        login(approver, "APPROVER");

        assertThat(regenerate.execute(DisplayNumber.draftNo(draftId)).pdfStatus()).isEqualTo(ReportPdfStatus.PENDING);

        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "PENDING").containsEntry("pdf_attempts", 1);   // 끊긴 시도도 센다
        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.READY);
        assertThat((String) pdfRow(draftId).get("pdf_storage_key")).endsWith("-2.pdf");
    }

    // 8
    @Test
    void 실패_막_만들기_시작한_PENDING은_다시_만들기를_받지_않는다() {          // 두 작업이 겹치지 않게
        long draftId = reportedDraft();
        login(approver, "APPROVER");

        assertThatThrownBy(() -> regenerate.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(ReportPdfRetryNotAllowedException.class);
        assertThat(pdfRow(draftId)).containsEntry("pdf_attempts", 0);
    }

    // ---- 도우미 ----

    /** 승인(P2) → 모의 제출(P4) → 만들기 실패(RENDER_FAILED). 케이스 REPORTED, pdf_status FAILED. */
    private long failedDraft() {
        long draftId = reportedDraft();
        assertThat(generate.fail(draftId, "RENDER_FAILED")).contains(ReportPdfStatus.FAILED);
        return draftId;
    }

    /** 승인(P2) → 모의 제출(P4). 케이스 REPORTED, 확정 보고서 pdf_status PENDING (자동 생성 꺼짐). */
    private long reportedDraft() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        login(approver, "APPROVER");
        reportDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId));
        SecurityContextHolder.clearContext();
        return draftId;
    }

    private Map<String, Object> pdfRow(long draftId) {
        return jdbc.queryForMap("select pdf_status, pdf_storage_key, pdf_sha256, pdf_size_bytes, pdf_template_version, "
                + "pdf_generated_at, pdf_failure_code, pdf_attempts from ops.str_reports where draft_id = ?", draftId);
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

    private void submit(long caseId, long draftId) {
        String verificationId = "v-" + draftId + "-" + SEQ.incrementAndGet();
        String hash = tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, 1, ?, 'PASSED', 'verify_v1', now(), '{}'::jsonb, ?)",
                verificationId, draftId, "g-" + draftId, hash, investigator);
        login(investigator, "INVESTIGATOR");
        submitDraft.execute(DisplayNumber.draftNo(draftId), caseVersion(caseId), verificationId, List.of("VI-1"), 3);
        SecurityContextHolder.clearContext();
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
                Long.class, "ALT-20240501-" + String.format("%06d", 890_000 + n), batchRunId, ruleId, "ACC-R" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 890_000 + n), alertId, "ACC-R" + n, investigator);
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
