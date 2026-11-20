package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.domain.DraftSupersededException;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrReport;
import com.team.aml.user.domain.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 보고서 PDF(S7 미리보기·S8 확정본)를 볼 수 있는가 — 검사 순서는 S4(ListStrRevisions)와 같다: 404 → 403 → 409.
 * 기준: 서식 회의 10/30 결정 4(본문·PDF 는 담당 조사역과 승인자만, 관리자는 상태·날짜만), 계약서 S7·S8 "담당·승인자".
 * PDF 는 본문 전체라 S2 처럼 제한 응답(META_ONLY)이 없다 — 403 NOT_ASSIGNEE (S4 와 같은 코드).
 */
final class StrReportPdfAccess {

    private StrReportPdfAccess() {}

    /** 승인자(누구나 — 승인함에서 보는 사람) 또는 지금 담당 조사역. GetStrDraft·ListStrRevisions 와 같은 기준. */
    static boolean canRead(Role role, Case c, long userId) {
        return role == Role.APPROVER || (role == Role.INVESTIGATOR && Objects.equals(c.getAssigneeId(), userId));
    }

    /** 확정본 PDF 다시 만들기 기본 대기 — PENDING 이 이만큼 그대로면 서버가 만드는 도중 죽은 것으로 본다 (보통 1~2초면 끝난다). */
    static final String STALE_PENDING_AFTER = "${aml.str.pdf.stale-pending-after:10m}";

    /**
     * P5 다시 만들기를 할 수 있는 사람·상태인가 — REGENERATE_PDF 버튼과 RegenerateStrReportPdf 가 같은 두 규칙을 쓴다:
     * 승인자 역할 + 이 초안을 승인한 사람({@link StrReport#isApprovedBy}, P4 와 같음) + 다시 만들 수 있는 상태({@link StrReport#isPdfRetryable}).
     * 서버는 403·409 를 나눠 주려고 두 규칙을 따로 검사하고, 버튼은 이걸로 한 번에 본다.
     */
    static boolean canRegenerate(Role role, StrReport report, long userId, Instant now, Duration staleAfter) {
        return role == Role.APPROVER && report.isApprovedBy(userId) && report.isPdfRetryable(now, staleAfter);
    }

    /** 403 NOT_ASSIGNEE. 열람 기록은 이 검사를 통과한 뒤에만(403·404 는 남기지 않는다, 11/2·11/3 결정). */
    static void requireReadable(Role role, Case c, long userId) {
        if (!canRead(role, c, userId)) {
            throw new NotAssigneeException(c.getCaseNo());
        }
    }

    /** 재생성으로 대체된 옛 초안이면 409 DRAFT_SUPERSEDED (details.current_draft_no — 화면이 그 번호로 다시 연다). */
    static void requireCurrentDraft(StrDraft draft, Case c) {
        Long current = c.getCurrentDraftId();
        if (current != null && !Objects.equals(current, draft.getId())) {
            throw new DraftSupersededException(draft.getId(), current);
        }
    }

    /** 케이스가 그 상태가 아니면 409 INVALID_CASE_TRANSITION (details.action = PREVIEW_PDF·DOWNLOAD_PDF). */
    static void requireStatus(Case c, CaseStatus expected, String action) {
        if (c.getStatus() != expected) {
            throw InvalidCaseTransitionException.notEditable(c.getStatus(), c.getRejectionReasonCode(), action);
        }
    }

    /** STR-2026-000001.pdf / STR-2026-000001-preview.pdf — 문서번호(ASCII)만 써서 Content-Disposition 이 단순하다. */
    static String fileName(String reportNo, boolean preview) {
        return reportNo + (preview ? "-preview" : "") + ".pdf";
    }
}
