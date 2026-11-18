package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.domain.BaseEntity;
import com.team.aml.shared.domain.DisplayNumber;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 확정 보고서 (ops.str_reports, V015). 서식 회의 10/30 결정 1·2·5-2·5-3.
 * <ul>
 *   <li>승인(P2) — {@link #approve}: 문서번호·마감·의심 유형·정도·본문 해시·서식 스냅샷(표제부~VII)을 고정. 이후 바뀌지 않는다.
 *       문서번호 연도는 UTC 가 아니라 서울 기준 승인일(11/17 결정).</li>
 *   <li>모의 제출(P4) — {@link #markReported}: 제출 시각·제출자·늦음 + PDF PENDING.</li>
 *   <li>PDF(제출 커밋 뒤) — {@link #pdfReady} / {@link #pdfFailed}. 실패해도 승인·제출은 그대로.</li>
 *   <li>다시 만들기(P5) — {@link #retryPdf}: FAILED → PENDING, 같은 스냅샷으로.</li>
 * </ul>
 * 상태는 메서드로만 바뀐다(setter 없음). 지우지 않는다(V015 가 DELETE 권한 회수 — 5년 보존).
 * 케이스 상태(APPROVED → REPORTED)는 cases 가 맡는다 — 여기는 STR 쪽 기록만.
 * created_by = 승인자, 낙관적 락은 @Version.
 */
@Entity
@Table(name = "str_reports")
public class StrReport extends BaseEntity {

    static final int MAX_SUSPICION_TYPE_LENGTH = 16;   // V015 suspicion_types varchar(16)[]
    static final int MAX_STORAGE_KEY_LENGTH = 512;     // V015 pdf_storage_key varchar(512)
    static final int MAX_VERSION_LENGTH = 32;          // V015 snapshot_version·pdf_template_version varchar(32)
    static final int MAX_FAILURE_CODE_LENGTH = 64;     // V015 pdf_failure_code varchar(64)
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Column(name = "approval_id", nullable = false, updatable = false)
    private Long approvalId;                 // 이 보고서를 만든 승인 기록 (decision = APPROVED)

    @Column(name = "report_no", nullable = false, updatable = false, length = 32)
    private String reportNo;                 // 서식 표제부 1. STR-2026-000001

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "report_due_at", nullable = false, updatable = false)
    private Instant reportDueAt;             // ReportDeadline — 승인 때 고정

    @Column(name = "reported_at")
    private Instant reportedAt;              // 서식 표제부 2 보고일자

    @Column(name = "reported_by")
    private Long reportedBy;

    @Column(name = "late")
    private Boolean late;                    // 마감일 다음 날 00:00 KST 이후 제출 (ReportDeadline.isLate)

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "suspicion_types", nullable = false, updatable = false)
    private String[] suspicionTypes;         // 서식 VI — 승인 때 str_drafts 에서 복사

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "suspicion_level_branch", nullable = false, updatable = false)
    private Integer suspicionLevelBranch;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "suspicion_level_hq", nullable = false, updatable = false)
    private Integer suspicionLevelHq;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "narrative_sha256", nullable = false, updatable = false, length = 64)
    private String narrativeSha256;          // 승인 당시 본문 해시 (approvals 와 같은 값)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_snapshot", nullable = false, updatable = false)
    private JsonNode formSnapshot;           // 표제부~VII + 내부 결재 정보. 최신 점수·바뀐 고객 정보로 다시 조립하지 않는다

    @Column(name = "snapshot_version", nullable = false, updatable = false, length = 32)
    private String snapshotVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "pdf_status", length = 16)
    private ReportPdfStatus pdfStatus;       // 제출 전에는 null

    @Column(name = "pdf_storage_key", length = MAX_STORAGE_KEY_LENGTH)
    private String pdfStorageKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pdf_sha256", length = 64)
    private String pdfSha256;

    @Column(name = "pdf_size_bytes")
    private Long pdfSizeBytes;

    @Column(name = "pdf_template_version", length = MAX_VERSION_LENGTH)
    private String pdfTemplateVersion;

    @Column(name = "pdf_generated_at")
    private Instant pdfGeneratedAt;

    @Column(name = "pdf_failure_code", length = MAX_FAILURE_CODE_LENGTH)
    private String pdfFailureCode;           // FAILED 일 때 이유 코드. 본문·계좌 값은 넣지 않는다

    @Column(name = "pdf_attempts", nullable = false)
    private int pdfAttempts;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA 용. */
    protected StrReport() {}

    /**
     * 승인(P2) 트랜잭션에서 부른다 — 초안을 잠그고(draft.lock) 승인 기록을 저장(flush)한 뒤.
     *
     * @param reportSeq      ops.str_report_no_seq 에서 받은 번호 ({@link StrReportRepository#nextReportSeq})
     * @param approvedAt     승인 시각 (UTC). 문서번호 연도·마감은 이 시각의 서울 날짜로 계산
     * @param formSnapshot   서식 표제부~VII 스냅샷 (조립은 application 몫). 보고일자는 비워 둔다 — 제출 때 PDF 에만
     */
    public static StrReport approve(StrDraft draft, Approval approval, long reportSeq, Instant approvedAt,
                                    JsonNode formSnapshot, String snapshotVersion) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (draft.getStatus() != StrDraftStatus.LOCKED) {
            throw new IllegalStateException("잠기지 않은 초안으로 확정 보고서를 만들 수 없다 — draft.lock() 뒤에 부른다: " + draft.getStatus());
        }
        if (approval.getDecision() != ApprovalDecision.APPROVED) {
            throw new IllegalArgumentException("승인 기록이 아니다: " + approval.getDecision());
        }
        if (approval.getId() == null) {
            throw new IllegalStateException("승인 기록이 아직 저장되지 않았다 — flush 뒤에 부른다");
        }
        if (!Objects.equals(approval.getDraftId(), draft.getId())) {
            throw new IllegalArgumentException("다른 초안의 승인 기록이다: " + approval.getDraftId());
        }
        if (reportSeq <= 0) {
            throw new IllegalArgumentException("문서번호 일련번호는 1 이상이어야 한다: " + reportSeq);
        }
        List<String> types = draft.getSuspicionTypes();
        if (types.isEmpty() || draft.getSuspicionLevelBranch() == null || draft.getSuspicionLevelHq() == null) {
            throw new IllegalStateException("의심 유형·지점 정도(S6)와 본점 정도(P2)가 정해진 뒤에 만든다");
        }
        if (formSnapshot == null || !formSnapshot.isObject()) {
            throw new IllegalArgumentException("서식 스냅샷은 JSON 객체여야 한다");
        }
        requireText(snapshotVersion, MAX_VERSION_LENGTH, "snapshotVersion");

        StrReport r = new StrReport();
        r.draftId = draft.getId();
        r.caseId = draft.getCaseId();
        r.approvalId = approval.getId();
        r.reportNo = DisplayNumber.reportNo(approvedAt.atZone(ReportDeadline.BUSINESS_ZONE).getYear(), reportSeq);
        r.approvedAt = approvedAt;
        r.reportDueAt = ReportDeadline.dueAt(approvedAt);
        r.suspicionTypes = types.toArray(String[]::new);
        r.suspicionLevelBranch = draft.getSuspicionLevelBranch();
        r.suspicionLevelHq = draft.getSuspicionLevelHq();
        r.narrativeSha256 = approval.getNarrativeSha256();
        r.formSnapshot = formSnapshot.deepCopy();
        r.snapshotVersion = snapshotVersion;
        return r;
    }

    /**
     * 모의 제출(P4). 케이스 APPROVED → REPORTED 는 같은 트랜잭션에서 cases ReportCase 가 한다 — 이미 REPORTED 면 거기서 먼저 409.
     * 여기서 다시 제출이면 호출 쪽 버그라 IllegalStateException.
     */
    public void markReported(Long reportedBy, Instant reportedAt) {
        Objects.requireNonNull(reportedBy, "reportedBy");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (isReported()) {
            throw new IllegalStateException("이미 모의 제출한 보고서다: " + reportNo);
        }
        this.reportedBy = reportedBy;
        // DB(timestamptz)는 마이크로초까지 — 드라이버(pgjdbc)·PostgreSQL 이 나노초를 마이크로초로 <b>반올림</b>해 저장한다.
        // 같은 규칙으로 맞춰야 ① 저장값과 late 판단이 같고 ② 같은 Instant 를 저장한 REPORT 이력 created_at 과 값이 같다 (#125, CI 실패 원인).
        // 자르면(truncate) 나노초 끝자리가 500 이상일 때 DB 값보다 1µs 작아진다.
        Instant atMicros = roundToMicros(reportedAt);
        this.reportedAt = atMicros;
        this.late = ReportDeadline.isLate(reportDueAt, atMicros);
        this.pdfStatus = ReportPdfStatus.PENDING;
    }

    /** 나노초 → 마이크로초 반올림(0.5µs 올림) — DB 가 저장하는 값과 같게. 23:59:59.9999995 는 다음 초 00.000000 이 된다. */
    static Instant roundToMicros(Instant t) {
        return t.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    /** PDF 생성 성공 (제출 커밋 뒤, 또는 다시 만들기 뒤). PENDING 일 때만. */
    public void pdfReady(String storageKey, String sha256, long sizeBytes, String templateVersion, Instant generatedAt) {
        requirePdfStatus(ReportPdfStatus.PENDING);
        requireText(storageKey, MAX_STORAGE_KEY_LENGTH, "storageKey");
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("PDF SHA-256 은 소문자 hex 64자여야 한다");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("PDF 크기는 1바이트 이상이어야 한다: " + sizeBytes);
        }
        requireText(templateVersion, MAX_VERSION_LENGTH, "templateVersion");
        Objects.requireNonNull(generatedAt, "generatedAt");
        this.pdfStatus = ReportPdfStatus.READY;
        this.pdfStorageKey = storageKey;
        this.pdfSha256 = sha256;
        this.pdfSizeBytes = sizeBytes;
        this.pdfTemplateVersion = templateVersion;
        this.pdfGeneratedAt = generatedAt;
        this.pdfFailureCode = null;
        this.pdfAttempts++;
    }

    /** PDF 생성 실패. PENDING 일 때만. 승인·제출은 되돌리지 않는다. */
    public void pdfFailed(String failureCode) {
        requirePdfStatus(ReportPdfStatus.PENDING);
        requireText(failureCode, MAX_FAILURE_CODE_LENGTH, "failureCode");
        this.pdfStatus = ReportPdfStatus.FAILED;
        this.pdfFailureCode = failureCode;
        this.pdfAttempts++;
    }

    /** 다시 만들기(P5) — FAILED 일 때만, 아니면 409 INVALID_CASE_TRANSITION(프론트 목업과 같음). 같은 스냅샷으로 다시 만든다. */
    public void retryPdf() {
        requireReported();
        if (pdfStatus != ReportPdfStatus.FAILED) {
            throw new ReportPdfRetryNotAllowedException(draftId, pdfStatus);
        }
        this.pdfStatus = ReportPdfStatus.PENDING;
        this.pdfFailureCode = null;
    }

    /** 확정본 받기(S8) 전에 — READY 가 아니면 409 REPORT_PDF_NOT_READY. 케이스 REPORTED 확인은 호출 쪽이 먼저 한다. */
    public void requirePdfReady() {
        requireReported();
        if (pdfStatus != ReportPdfStatus.READY) {
            throw new ReportPdfNotReadyException(pdfStatus);
        }
    }

    public boolean isReported() { return reportedAt != null; }

    private void requireReported() {
        if (!isReported()) {
            throw new IllegalStateException("모의 제출 전이라 PDF 가 없다 — 케이스 REPORTED 확인을 먼저 한다: " + reportNo);
        }
    }

    private void requirePdfStatus(ReportPdfStatus expected) {
        requireReported();
        if (pdfStatus != expected) {
            throw new IllegalStateException("PDF 상태가 " + expected + " 가 아니다: " + pdfStatus);
        }
    }

    private static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " 는 1~" + max + "자여야 한다");
        }
    }

    public Long getDraftId() { return draftId; }
    public Long getCaseId() { return caseId; }
    public Long getApprovalId() { return approvalId; }
    public String getReportNo() { return reportNo; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getReportDueAt() { return reportDueAt; }
    public Instant getReportedAt() { return reportedAt; }
    public Long getReportedBy() { return reportedBy; }
    public Boolean getLate() { return late; }
    public List<String> getSuspicionTypes() { return List.of(suspicionTypes); }
    public Integer getSuspicionLevelBranch() { return suspicionLevelBranch; }
    public Integer getSuspicionLevelHq() { return suspicionLevelHq; }
    public String getNarrativeSha256() { return narrativeSha256; }
    public JsonNode getFormSnapshot() { return formSnapshot.deepCopy(); }
    public String getSnapshotVersion() { return snapshotVersion; }
    public ReportPdfStatus getPdfStatus() { return pdfStatus; }
    public String getPdfStorageKey() { return pdfStorageKey; }
    public String getPdfSha256() { return pdfSha256; }
    public Long getPdfSizeBytes() { return pdfSizeBytes; }
    public String getPdfTemplateVersion() { return pdfTemplateVersion; }
    public Instant getPdfGeneratedAt() { return pdfGeneratedAt; }
    public String getPdfFailureCode() { return pdfFailureCode; }
    public int getPdfAttempts() { return pdfAttempts; }
    public long getVersion() { return version; }
}
