package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.domain.DraftSupersededException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
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
 * PreviewStrReportPdf (S7 제출 전 미리보기) — 실제 PostgreSQL + 실제 PDF. 승인까지는 S6·P2 로 실제로 만든다.
 * 담당 조사역·승인자만, 케이스 APPROVED 에서만, 저장하지 않고, 열람 기록 PDF_DOWNLOAD 한 줄.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PreviewStrReportPdfDbTest {

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

    @Autowired PreviewStrReportPdf preview;
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
        investigator = user("pv-inv" + n, "INVESTIGATOR");
        approver = user("pv-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-08-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_400 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 정상_승인자는_제출_전_표시가_있는_PDF를_받고_저장은_안_하고_열람_기록_한_줄() throws Exception {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        String reportNo = reportNo(draftId);
        login(approver, "APPROVER");

        StrPdfFile file = preview.execute(DisplayNumber.draftNo(draftId));

        assertThat(file.fileName()).isEqualTo(reportNo + "-preview.pdf");
        try (PDDocument doc = Loader.loadPDF(file.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("의심스러운 거래보고서", reportNo, "제출 전 미리보기", "AI GROUNDS 문장");
        }
        assertThat(pdfDownloads(draftId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select pdf_status, pdf_storage_key, pdf_attempts from ops.str_reports "
                + "where draft_id = ?", draftId);
        assertThat(row).containsEntry("pdf_status", null).containsEntry("pdf_storage_key", null)
                .containsEntry("pdf_attempts", 0);                       // 미리보기는 저장하지 않는다 (결정 2)
    }

    // 2
    @Test
    void 정상_담당_조사역도_미리보기를_받는다() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        StrPdfFile file = preview.execute(DisplayNumber.draftNo(draftId));

        assertThat(file.bytes()).isNotEmpty();
        assertThat(pdfDownloads(draftId)).isEqualTo(1);
    }

    // 3
    @Test
    void 실패_비담당_조사역과_관리자는_403_NOT_ASSIGNEE이고_기록_없음() {   // 결정 4 — PDF 는 본문 전체
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        long other = user("pv-inv-other" + SEQ.incrementAndGet(), "INVESTIGATOR");
        long admin = user("pv-adm" + SEQ.incrementAndGet(), "ADMIN");

        login(other, "INVESTIGATOR");
        assertThatThrownBy(() -> preview.execute(DisplayNumber.draftNo(draftId))).isInstanceOf(NotAssigneeException.class);
        login(admin, "ADMIN");
        assertThatThrownBy(() -> preview.execute(DisplayNumber.draftNo(draftId))).isInstanceOf(NotAssigneeException.class);

        assertThat(pdfDownloads(draftId)).isZero();
    }

    // 4
    @Test
    void 실패_승인_전이면_409_INVALID_CASE_TRANSITION_action_PREVIEW_PDF() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        submit(caseId, draftId);                                          // PENDING_APPROVAL — 보고서도 아직 없다
        login(approver, "APPROVER");

        assertThatThrownBy(() -> preview.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                        e -> assertThat(e.details()).containsEntry("action", "PREVIEW_PDF"));
        assertThat(pdfDownloads(draftId)).isZero();
    }

    // 5
    @Test
    void 실패_제출_뒤에는_미리보기가_아니라_확정본이라_409() {
        long caseId = escalatedCase();
        long draftId = approvedDraft(caseId);
        jdbc.update("update ops.cases set status = 'REPORTED' where id = ?", caseId);   // 전이는 ReportStrDraftDbTest 몫
        login(approver, "APPROVER");

        assertThatThrownBy(() -> preview.execute(DisplayNumber.draftNo(draftId)))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    // 6
    @Test
    void 실패_대체된_초안이면_409_DRAFT_SUPERSEDED_현재_초안_번호와_함께() {
        long caseId = escalatedCase();
        long oldDraftId = savedDraft(caseId);
        long newDraftId = approvedDraft(caseId);                          // 다시 만든 초안이 승인됨
        login(approver, "APPROVER");

        assertThatThrownBy(() -> preview.execute(DisplayNumber.draftNo(oldDraftId)))
                .isInstanceOfSatisfying(DraftSupersededException.class,
                        e -> assertThat(e.details()).containsEntry("current_draft_no", DisplayNumber.draftNo(newDraftId)));
    }

    // 7
    @Test
    void 실패_없는_초안_번호면_404이고_기록_없음() {
        login(approver, "APPROVER");

        assertThatThrownBy(() -> preview.execute("DRF-999999")).isInstanceOf(DraftNotFoundException.class);
        assertThatThrownBy(() -> preview.execute("draft-1")).isInstanceOf(DraftNotFoundException.class);
    }

    // ---- 도우미 (ReportStrDraftDbTest 와 같은 모양) ----

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
                Long.class, "ALT-20240501-" + String.format("%06d", 860_000 + n), batchRunId, ruleId, "ACC-V" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 860_000 + n), alertId, "ACC-V" + n, investigator);
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
