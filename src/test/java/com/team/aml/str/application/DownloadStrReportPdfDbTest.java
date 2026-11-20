package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.ReportPdfNotReadyException;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정본 PDF 받기(DownloadStrReportPdf, S8) — 실제 PostgreSQL + 실제 PDF + 로컬 보관 폴더.
 * 커밋 뒤 자동 생성은 끄고 GenerateStrReportPdf 를 직접 불러 READY 를 만든다. 보관본을 그대로·해시 대조·열람 기록 PDF_DOWNLOAD.
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.str.pdf.generate-after-report=false",
        "aml.str.pdf.storage-dir=build/str-reports-test/download"})
@ActiveProfiles("test")
class DownloadStrReportPdfDbTest {

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

    @Autowired GenerateStrReportPdf generate;
    @Autowired DownloadStrReportPdf download;
    @Autowired ReportStrDraft reportDraft;
    @Autowired ApproveStrDraft approveDraft;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Value("${aml.str.pdf.storage-dir}") String storageDir;

    long investigator;
    long approver;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("dl-inv" + n, "INVESTIGATOR");
        approver = user("dl-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-10-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_600 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 정상_S8은_보관본을_그대로_주고_열람_기록_한_줄_담당_조사역도_받는다() throws IOException {
        long draftId = reportedDraft();
        generate.execute(draftId);
        String reportNo = reportNo(draftId);
        login(approver, "APPROVER");

        StrPdfFile file = download.execute(DisplayNumber.draftNo(draftId));

        assertThat(file.fileName()).isEqualTo(reportNo + ".pdf");
        assertThat(StrPdfDigest.sha256(file.bytes())).isEqualTo(pdfRow(draftId).get("pdf_sha256"));
        try (PDDocument doc = Loader.loadPDF(file.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains(reportNo, "모의 제출본", "(KST)").doesNotContain("제출 전 미리보기");
        }
        login(investigator, "INVESTIGATOR");
        assertThat(download.execute(DisplayNumber.draftNo(draftId)).bytes()).isEqualTo(file.bytes());
        assertThat(pdfDownloads(draftId)).isEqualTo(2);
    }

    // 2
    @Test
    void 실패_아직_만드는_중이면_409_REPORT_PDF_NOT_READY_PENDING이고_기록_없음() {
        long draftId = reportedDraft();                                    // 자동 생성 꺼짐 — PENDING 그대로
        login(approver, "APPROVER");

        assertThatThrownBy(() -> download.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(ReportPdfNotReadyException.class,
                        e -> assertThat(e.details()).containsEntry("pdf_status", "PENDING"));
        assertThat(pdfDownloads(draftId)).isZero();
    }

    // 3
    @Test
    void 실패_만들기에_실패했으면_409_REPORT_PDF_NOT_READY_FAILED() {   // 화면은 승인자에게 [PDF 다시 만들기](P5)
        long draftId = reportedDraft();
        generate.fail(draftId, "RENDER_FAILED");
        login(approver, "APPROVER");

        assertThatThrownBy(() -> download.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(ReportPdfNotReadyException.class,
                        e -> assertThat(e.details()).containsEntry("pdf_status", "FAILED"));
        assertThat(pdfDownloads(draftId)).isZero();
    }

    // 4
    @Test
    void 실패_보관본이_바뀌었으면_내보내지_않고_500_기록_없음() throws IOException {
        long draftId = reportedDraft();
        generate.execute(draftId);
        Files.write(Path.of(storageDir).resolve((String) pdfRow(draftId).get("pdf_storage_key")), new byte[]{1, 2, 3});
        login(approver, "APPROVER");

        assertThatThrownBy(() -> download.execute(DisplayNumber.draftNo(draftId))).isInstanceOf(IllegalStateException.class);
        assertThat(pdfDownloads(draftId)).isZero();
    }

    // 5
    @Test
    void 실패_제출_전이면_S8은_409_INVALID_CASE_TRANSITION_action_DOWNLOAD_PDF() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);                              // APPROVED — 미리보기(S7) 단계
        login(approver, "APPROVER");

        assertThatThrownBy(() -> download.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                        e -> assertThat(e.details()).containsEntry("action", "DOWNLOAD_PDF"));
    }

    // 6
    @Test
    void 실패_비담당_조사역은_403_NOT_ASSIGNEE() {
        long draftId = reportedDraft();
        generate.execute(draftId);
        login(user("dl-inv-other" + SEQ.incrementAndGet(), "INVESTIGATOR"), "INVESTIGATOR");

        assertThatThrownBy(() -> download.execute(DisplayNumber.draftNo(draftId))).isInstanceOf(NotAssigneeException.class);
        assertThat(pdfDownloads(draftId)).isZero();
    }

    // ---- 도우미 ----

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

    private long pdfDownloads(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.access_logs where action = 'PDF_DOWNLOAD' "
                + "and target_type = 'STR_DRAFT' and target_id = ?", Long.class, draftId);
    }

    private String reportNo(long draftId) {
        return jdbc.queryForObject("select report_no from ops.str_reports where draft_id = ?", String.class, draftId);
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
                Long.class, "ALT-20240501-" + String.format("%06d", 880_000 + n), batchRunId, ruleId, "ACC-D" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 880_000 + n), alertId, "ACC-D" + n, investigator);
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
