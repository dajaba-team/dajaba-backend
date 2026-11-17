package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정 보고서 (스프링 없이). 기준: V015 str_reports CHECK, 서식 회의 10/30 결정 1·2·5-3, str.md 10~13번(P4·S7·S8·P5).
 */
class StrReportTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Instant APPROVED_AT = Instant.parse("2026-11-17T05:00:00Z");   // 서울 11-17(화) 14:00
    private static final Instant DUE_AT = Instant.parse("2026-11-20T14:59:59Z");        // 서울 11-20(금) 23:59:59
    private static final String SHA = "b".repeat(64);

    private static StrDraft newDraft() {
        ObjectNode payload = OM.createObjectNode();
        payload.putObject("facts").put("total_amount", 98_000_000);
        List<NarrativeSection> body = StrSection.V2.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of("period"), List.of(1L)))
                .toList();
        return StrDraft.create(100L, 10L, "gen-1", "narrative_v2", Instant.parse("2026-11-03T01:10:00Z"), payload, body);
    }

    /** S6·P2 까지 마친 초안 + 저장된 승인 기록 (id 는 DB 가 주는 값을 흉내). */
    private static StrDraft lockedDraft() {
        StrDraft draft = newDraft();
        draft.recordBranchSuspicion(List.of("VI-2-9", "VI-4-26"), 3);
        draft.recordHqSuspicion(4);
        return draft;
    }

    private static Approval savedApproval(StrDraft draft) {
        VerificationResult v = VerificationResult.record(100L, "v-1", "gen-1", 1, NarrativeHash.of(draft.currentBody()),
                VerificationStatus.PASSED, "verify_v1", Instant.parse("2026-11-03T02:00:00Z"), OM.createObjectNode());
        ReflectionTestUtils.setField(v, "id", 7L);
        Approval a = Approval.approved(draft, v);
        ReflectionTestUtils.setField(a, "id", 55L);
        return a;
    }

    private static ObjectNode snapshot() {
        ObjectNode s = OM.createObjectNode();
        s.putObject("header").putNull("reported_date");
        return s;
    }

    private static StrReport approvedReport() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        draft.lock();
        return StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), "form_v1");
    }

    private static StrReport reportedReport() {
        StrReport r = approvedReport();
        r.markReported(3L, Instant.parse("2026-11-18T01:00:00Z"));
        return r;
    }

    // ── 승인 (P2) ──────────────────────────────────────────

    // 1
    @Test
    void 정상_승인때_문서번호_마감_의심유형정도_해시_스냅샷이_고정되고_PDF는_없다() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        draft.lock();

        StrReport r = StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), "form_v1");

        assertThat(r.getReportNo()).isEqualTo("STR-2026-000001");
        assertThat(r.getDraftId()).isEqualTo(100L);
        assertThat(r.getCaseId()).isEqualTo(10L);
        assertThat(r.getApprovalId()).isEqualTo(55L);
        assertThat(r.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(r.getReportDueAt()).isEqualTo(DUE_AT);
        assertThat(r.getSuspicionTypes()).containsExactly("VI-2-9", "VI-4-26");
        assertThat(r.getSuspicionLevelBranch()).isEqualTo(3);
        assertThat(r.getSuspicionLevelHq()).isEqualTo(4);
        assertThat(r.getNarrativeSha256()).isEqualTo(approval.getNarrativeSha256());
        assertThat(r.getFormSnapshot()).isEqualTo(snapshot());
        assertThat(r.isReported()).isFalse();
        assertThat(r.getLate()).isNull();
        assertThat(r.getPdfStatus()).isNull();
        assertThat(r.getPdfAttempts()).isZero();
    }

    // 2
    @Test
    void 정상_문서번호_연도는_UTC가_아니라_서울_승인일() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        draft.lock();

        StrReport r = StrReport.approve(draft, approval, 42L, Instant.parse("2026-12-31T15:30:00Z"), snapshot(), "form_v1");

        assertThat(r.getReportNo()).isEqualTo("STR-2027-000042");   // 서울은 2027-01-01 00:30
    }

    // 3
    @Test
    void 정상_스냅샷은_복사본이라_밖에서_바꿔도_그대로() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        draft.lock();
        ObjectNode given = snapshot();

        StrReport r = StrReport.approve(draft, approval, 1L, APPROVED_AT, given, "form_v1");
        given.put("tampered", true);
        ((ObjectNode) r.getFormSnapshot()).put("tampered", true);

        assertThat(r.getFormSnapshot().has("tampered")).isFalse();
    }

    // 4
    @Test
    void 예외_잠기지_않은_초안이면_거부() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);   // lock() 안 함

        assertThatThrownBy(() -> StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // 5
    @Test
    void 예외_반려_기록이나_저장_전_승인_기록이면_거부() {
        StrDraft draft = lockedDraft();
        Approval rejected = Approval.rejected(draft, com.team.aml.cases.domain.RejectionReasonCode.NEED_REVISION, "보완");
        ReflectionTestUtils.setField(rejected, "id", 56L);
        VerificationResult v = VerificationResult.record(100L, "v-2", "gen-1", 1, NarrativeHash.of(draft.currentBody()),
                VerificationStatus.PASSED, "verify_v1", Instant.parse("2026-11-03T02:00:00Z"), OM.createObjectNode());
        Approval unsaved = Approval.approved(draft, v);
        draft.lock();

        assertThatThrownBy(() -> StrReport.approve(draft, rejected, 1L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrReport.approve(draft, unsaved, 1L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // 6
    @Test
    void 예외_다른_초안의_승인_기록이면_거부() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        ReflectionTestUtils.setField(approval, "draftId", 999L);
        draft.lock();

        assertThatThrownBy(() -> StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 7
    @Test
    void 예외_본점용_의심정도가_없으면_거부() {
        StrDraft draft = newDraft();
        draft.recordBranchSuspicion(List.of("VI-2-9"), 3);   // recordHqSuspicion 안 함
        Approval approval = savedApproval(draft);
        draft.lock();

        assertThatThrownBy(() -> StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // 8
    @Test
    void 예외_스냅샷이_객체가_아니거나_버전이_비면_거부() {
        StrDraft draft = lockedDraft();
        Approval approval = savedApproval(draft);
        draft.lock();

        assertThatThrownBy(() -> StrReport.approve(draft, approval, 1L, APPROVED_AT, OM.createArrayNode(), "form_v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrReport.approve(draft, approval, 1L, APPROVED_AT, snapshot(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StrReport.approve(draft, approval, 0L, APPROVED_AT, snapshot(), "form_v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 모의 제출 (P4) ─────────────────────────────────────

    // 9
    @Test
    void 정상_마감_전_제출은_late_false이고_PDF_PENDING() {
        StrReport r = approvedReport();

        r.markReported(3L, DUE_AT);   // 마감 그 순간까지는 늦음 아님

        assertThat(r.isReported()).isTrue();
        assertThat(r.getReportedBy()).isEqualTo(3L);
        assertThat(r.getReportedAt()).isEqualTo(DUE_AT);
        assertThat(r.getLate()).isFalse();
        assertThat(r.getPdfStatus()).isEqualTo(ReportPdfStatus.PENDING);
    }

    // 10
    @Test
    void 정상_마감_뒤_제출은_late_true() {
        StrReport r = approvedReport();

        r.markReported(3L, DUE_AT.plusSeconds(1));   // 다음 날 00:00:00 KST

        assertThat(r.getLate()).isTrue();
    }

    // 10-1
    @Test
    void 경계_23시59분59초_소수초_제출은_late_false() {
        StrReport half = approvedReport();
        StrReport lastMicro = approvedReport();

        half.markReported(3L, DUE_AT.plusMillis(500));          // 23:59:59.500
        lastMicro.markReported(3L, DUE_AT.plusNanos(999_999_000)); // 23:59:59.999999 (DB 최대 정밀도)

        assertThat(half.getLate()).isFalse();
        assertThat(lastMicro.getLate()).isFalse();
        assertThat(lastMicro.getReportedAt()).isEqualTo(Instant.parse("2026-11-20T14:59:59.999999Z"));
    }

    // 10-2
    @Test
    void 경계_나노초는_DB처럼_마이크로초로_잘라_저장값과_late가_같다() {
        StrReport r = approvedReport();

        r.markReported(3L, DUE_AT.plusNanos(999_999_999));      // 23:59:59.999999999 → .999999

        assertThat(r.getReportedAt()).isEqualTo(Instant.parse("2026-11-20T14:59:59.999999Z"));
        assertThat(r.getLate()).isFalse();
    }

    // 11
    @Test
    void 예외_두번_제출은_호출쪽_버그() {
        StrReport r = reportedReport();

        assertThatThrownBy(() -> r.markReported(3L, Instant.parse("2026-11-18T02:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── PDF (제출 커밋 뒤, S8, P5) ─────────────────────────

    // 12
    @Test
    void 정상_PDF_성공이면_READY와_파일정보() {
        StrReport r = reportedReport();
        Instant at = Instant.parse("2026-11-18T01:00:05Z");

        r.pdfReady("reports/2026/STR-2026-000001.pdf", SHA, 1234L, "tpl_v1", at);

        assertThat(r.getPdfStatus()).isEqualTo(ReportPdfStatus.READY);
        assertThat(r.getPdfStorageKey()).isEqualTo("reports/2026/STR-2026-000001.pdf");
        assertThat(r.getPdfSha256()).isEqualTo(SHA);
        assertThat(r.getPdfSizeBytes()).isEqualTo(1234L);
        assertThat(r.getPdfTemplateVersion()).isEqualTo("tpl_v1");
        assertThat(r.getPdfGeneratedAt()).isEqualTo(at);
        assertThat(r.getPdfAttempts()).isEqualTo(1);
        r.requirePdfReady();   // 예외 없음
    }

    // 13
    @Test
    void 정상_실패하면_FAILED와_코드_그리고_다시만들기로_PENDING_뒤_READY() {
        StrReport r = reportedReport();

        r.pdfFailed("RENDER_ERROR");
        assertThat(r.getPdfStatus()).isEqualTo(ReportPdfStatus.FAILED);
        assertThat(r.getPdfFailureCode()).isEqualTo("RENDER_ERROR");
        assertThat(r.isReported()).isTrue();   // 제출은 되돌리지 않는다

        r.retryPdf();
        assertThat(r.getPdfStatus()).isEqualTo(ReportPdfStatus.PENDING);
        assertThat(r.getPdfFailureCode()).isNull();

        r.pdfReady("reports/2026/STR-2026-000001.pdf", SHA, 1234L, "tpl_v1", Instant.parse("2026-11-18T03:00:00Z"));
        assertThat(r.getPdfAttempts()).isEqualTo(2);
    }

    // 14
    @Test
    void 예외_READY가_아니면_받기는_409_REPORT_PDF_NOT_READY와_pdf_status() {
        StrReport pending = reportedReport();
        StrReport failed = reportedReport();
        failed.pdfFailed("RENDER_ERROR");

        assertThatThrownBy(pending::requirePdfReady)
                .isInstanceOfSatisfying(ReportPdfNotReadyException.class, e -> {
                    assertThat(e.code()).isEqualTo("REPORT_PDF_NOT_READY");
                    assertThat(e.details()).containsEntry("pdf_status", "PENDING");
                });
        assertThatThrownBy(failed::requirePdfReady)
                .isInstanceOfSatisfying(ReportPdfNotReadyException.class,
                        e -> assertThat(e.details()).containsEntry("pdf_status", "FAILED"));
    }

    // 15
    @Test
    void 예외_FAILED가_아니면_다시만들기는_409_INVALID_CASE_TRANSITION_프론트_목업과_같음() {
        StrReport pending = reportedReport();
        StrReport ready = reportedReport();
        ready.pdfReady("k", SHA, 1L, "tpl_v1", Instant.parse("2026-11-18T03:00:00Z"));

        assertThatThrownBy(pending::retryPdf)
                .isInstanceOfSatisfying(ReportPdfRetryNotAllowedException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_CASE_TRANSITION");
                    assertThat(e.details()).containsEntry("action", "REGENERATE_PDF")
                            .containsEntry("draft_no", "DRF-000100")
                            .containsEntry("pdf_status", "PENDING");
                });
        assertThatThrownBy(ready::retryPdf)
                .isInstanceOfSatisfying(ReportPdfRetryNotAllowedException.class,
                        e -> assertThat(e.details()).containsEntry("pdf_status", "READY"));
    }

    // 16
    @Test
    void 예외_제출_전에는_PDF_동작이_전부_호출쪽_버그() {
        StrReport r = approvedReport();

        assertThatThrownBy(r::requirePdfReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(r::retryPdf).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.pdfFailed("X")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.pdfReady("k", SHA, 1L, "tpl_v1", Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    // 17
    @Test
    void 예외_READY에서_다시_성공_실패는_거부_그리고_잘못된_파일정보는_거부() {
        StrReport r = reportedReport();

        assertThatThrownBy(() -> r.pdfReady("k", "ABC", 1L, "tpl_v1", Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.pdfReady("k", SHA, 0L, "tpl_v1", Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.pdfReady(" ", SHA, 1L, "tpl_v1", Instant.now())).isInstanceOf(IllegalArgumentException.class);

        r.pdfReady("k", SHA, 1L, "tpl_v1", Instant.parse("2026-11-18T03:00:00Z"));
        assertThatThrownBy(() -> r.pdfFailed("X")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.pdfReady("k", SHA, 1L, "tpl_v1", Instant.now())).isInstanceOf(IllegalStateException.class);
    }
}
