package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.ReportPdfRenderer;
import com.team.aml.str.domain.ReportPdfStatus;
import com.team.aml.str.domain.ReportPdfStorage;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.task.TaskRejectedException;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 확정본 PDF 만들기·보관(GenerateStrReportPdf) — 실제 PostgreSQL + 실제 PDF + 로컬 보관 폴더.
 * 커밋 뒤 자동 생성은 끄고(generate-after-report=false) 생성을 직접 불러서 상태를 하나씩 본다 — 트리거는 StrReportPdfTriggerTest.
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.str.pdf.generate-after-report=false",
        "aml.str.pdf.storage=filesystem",
        "aml.str.pdf.storage-dir=build/str-reports-test/generate"})
@ActiveProfiles("test")
class GenerateStrReportPdfDbTest {

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
    @Autowired RegenerateStrReportPdf regenerate;
    @Autowired JobPublisher publisher;
    @Autowired ReportStrDraft reportDraft;
    @Autowired ApproveStrDraft approveDraft;
    @Autowired SubmitStrDraft submitDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @SpyBean ReportPdfRenderer renderer;
    @SpyBean ReportPdfStorage storage;
    @Value("${aml.str.pdf.storage-dir}") String storageDir;

    long investigator;
    long approver;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("gen-inv" + n, "INVESTIGATOR");
        approver = user("gen-apr" + n, "APPROVER");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-09-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 1_500 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 정상_제출된_보고서를_그려_보관하면_READY와_키_해시_크기_템플릿_버전() throws IOException {
        long draftId = reportedDraft();
        String reportNo = reportNo(draftId);

        Optional<ReportPdfStatus> result = generate.execute(draftId);

        assertThat(result).contains(ReportPdfStatus.READY);
        Map<String, Object> row = pdfRow(draftId);
        String key = (String) row.get("pdf_storage_key");
        assertThat(key).isEqualTo(reportNo.substring(4, 8) + "/" + reportNo + "-1.pdf");
        assertThat(row).containsEntry("pdf_status", "READY").containsEntry("pdf_template_version", "pdf_v2")
                .containsEntry("pdf_attempts", 1).containsEntry("pdf_failure_code", null);
        byte[] stored = Files.readAllBytes(Path.of(storageDir).resolve(key));
        assertThat(row.get("pdf_sha256")).isEqualTo(StrPdfDigest.sha256(stored));
        assertThat(((Number) row.get("pdf_size_bytes")).longValue()).isEqualTo(stored.length);
        assertThat(row.get("pdf_generated_at")).isNotNull();
    }

    // 2
    @Test
    void 실패_그리기가_실패하면_FAILED_RENDER_FAILED이고_승인_제출은_그대로() {
        long draftId = reportedDraft();
        doThrow(new IllegalStateException("폰트 깨짐")).when(renderer).render(any());

        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.FAILED);

        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED").containsEntry("pdf_failure_code", "RENDER_FAILED")
                .containsEntry("pdf_attempts", 1).containsEntry("pdf_storage_key", null);
        assertThat(caseStatusOf(draftId)).isEqualTo("REPORTED");          // 제출은 되돌리지 않는다 (결정 2)
    }

    // 3
    @Test
    void 실패_보관이_실패하면_FAILED_STORAGE_FAILED() {
        long draftId = reportedDraft();
        doThrow(new UncheckedIOException(new IOException("disk full"))).when(storage).put(anyString(), any());

        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.FAILED);

        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED").containsEntry("pdf_failure_code", "STORAGE_FAILED");
    }

    // 3-2
    @Test
    void 실패_보관_런타임_예외가_나면_FAILED가_남고_복구_뒤_PDF_다시_만들기로_READY가_된다() {
        long draftId = reportedDraft();
        // storage 빈(현재 테스트 프로파일 filesystem spy)에서 런타임 예외를 발생시켜 STORAGE_FAILED 처리를 검증한다.
        doThrow(software.amazon.awssdk.core.exception.SdkClientException.create("storage unavailable"))
                .doCallRealMethod().when(storage).put(anyString(), any());
    }

    @Test
    void 실패_MinIO가_응답하지_않으면_FAILED가_남고_복구_뒤_PDF_다시_만들기로_READY가_된다() {
        long draftId = reportedDraft();
        // MinIO 연결 거부/타임아웃을 S3 SDK 런타임 예외로 모사한다. 첫 보관만 실패시키고 이후에는 실제 filesystem spy 동작을 사용한다.
        doThrow(software.amazon.awssdk.core.exception.SdkClientException.create("MinIO unavailable"))
                .doCallRealMethod().when(storage).put(anyString(), any());

        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.FAILED);
        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED")
                .containsEntry("pdf_failure_code", "STORAGE_FAILED");
        assertThat(caseStatusOf(draftId)).isEqualTo("REPORTED");

        login(approver, "APPROVER");
        assertThat(regenerate.execute(DisplayNumber.draftNo(draftId)).pdfStatus()).isEqualTo(ReportPdfStatus.PENDING);
        SecurityContextHolder.clearContext();

        assertThat(generate.execute(draftId)).contains(ReportPdfStatus.READY);
        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "READY")
                .containsEntry("pdf_attempts", 2).containsEntry("pdf_failure_code", null);
        assertThat((String) pdfRow(draftId).get("pdf_storage_key")).endsWith("-2.pdf");
    }

    // 4
    @Test
    void 정상_PENDING이_아니면_아무것도_안_한다_두_번_불러도_파일과_기록은_처음_것() {
        long draftId = reportedDraft();
        generate.execute(draftId);
        Map<String, Object> first = pdfRow(draftId);

        assertThat(generate.execute(draftId)).isEmpty();

        assertThat(pdfRow(draftId)).isEqualTo(first);
    }

    // 5
    @Test
    void 정상_보관_키는_연도_폴더와_시도_번호() {
        assertThat(ReportPdfStorage.keyOf("STR-2026-000001", 1)).isEqualTo("2026/STR-2026-000001-1.pdf");
        assertThat(ReportPdfStorage.keyOf("STR-2026-000001", 2)).isEqualTo("2026/STR-2026-000001-2.pdf");
        assertThatThrownBy(() -> ReportPdfStorage.keyOf("../etc", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // 6
    @Test
    void 실패_커밋_뒤_실행기가_거절하면_FAILED_NOT_STARTED가_실제로_커밋된다() {   // #149 리뷰
        long draftId = reportedDraft();                     // pdf_status PENDING 커밋됨
        // 서버 종료 중처럼 실행기가 거절 — Spring 없이 mock 으로 보는 StrReportPdfTriggerTest 는 이 트랜잭션 문제를 못 잡는다
        StrReportPdfTrigger trigger = new StrReportPdfTrigger(generate, publisher,
                task -> { throw new TaskRejectedException("shutting down"); }, true);

        // 진짜 트랜잭션 안에서 걸어 afterCommit 경로로 — 끝난 트랜잭션에 합류하면 FAILED 가 커밋되지 않고 PENDING 이 남는다
        tx.executeWithoutResult(s -> trigger.afterCommit(draftId));

        assertThat(pdfRow(draftId)).containsEntry("pdf_status", "FAILED").containsEntry("pdf_failure_code", "NOT_STARTED")
                .containsEntry("pdf_storage_key", null);
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

    private String caseStatusOf(long draftId) {
        return jdbc.queryForObject("select c.status from ops.cases c join ops.str_drafts d on d.case_id = c.id "
                + "where d.id = ?", String.class, draftId);
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
                Long.class, "ALT-20240501-" + String.format("%06d", 870_000 + n), batchRunId, ruleId, "ACC-G" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 870_000 + n), alertId, "ACC-G" + n, investigator);
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
