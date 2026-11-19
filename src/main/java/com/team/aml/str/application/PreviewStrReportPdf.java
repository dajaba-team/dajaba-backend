package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.ReportPdfContent;
import com.team.aml.str.domain.ReportPdfRenderer;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출 전 PDF 미리보기 (계약서 S7, 11/4 MVP 포함 결정). 승인~모의 제출 사이에 확정본 모양을 미리 본다.
 * 서식 회의 10/30 결정 2 — <b>저장하지 않고</b> 부를 때마다 승인 때 고정한 스냅샷으로 만든다. 보고일자 칸은 비우고 모든 쪽에 "제출 전".
 *
 * <p>순서: 초안 404 → 담당 조사역·승인자가 아니면 403 NOT_ASSIGNEE → 대체된 초안 409 DRAFT_SUPERSEDED
 * → 케이스 APPROVED 가 아니면 409 INVALID_CASE_TRANSITION(action PREVIEW_PDF — 제출 뒤엔 S8 확정본)
 * → 열람 기록 PDF_DOWNLOAD → PDF.
 *
 * <p>열람 기록: 미리보기도 본문 전체가 담긴 PDF 를 내보내므로 S8 과 같은 PDF_DOWNLOAD 로 남긴다(결정 4 — 본문 열람·PDF 다운로드는 access_logs).
 * 기록과 PDF 만들기를 한 트랜잭션에 둬서, PDF 를 못 만들면 기록도 남지 않는다(돌려준 열람만 남김 — 11/3 결정).
 */
@Service
public class PreviewStrReportPdf {

    static final String ACTION = "PREVIEW_PDF";

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final StrReportRepository reports;
    private final ReportPdfRenderer renderer;
    private final CurrentUser currentUser;
    private final AccessLogWriter accessLog;

    public PreviewStrReportPdf(StrDraftRepository drafts, CaseRepository cases, StrReportRepository reports,
                               ReportPdfRenderer renderer, CurrentUser currentUser, AccessLogWriter accessLog) {
        this.drafts = drafts;
        this.cases = cases;
        this.reports = reports;
        this.renderer = renderer;
        this.currentUser = currentUser;
        this.accessLog = accessLog;
    }

    /** readOnly 아님 — accessLog.pdfDownload 가 MANDATORY·쓰기 트랜잭션을 요구한다. */
    @Transactional
    public StrPdfFile execute(String draftNo) {
        AppUserDetails me = currentUser.require();
        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));

        StrReportPdfAccess.requireReadable(Role.valueOf(me.role()), c, me.userId());
        StrReportPdfAccess.requireCurrentDraft(draft, c);
        StrReportPdfAccess.requireStatus(c, CaseStatus.APPROVED, ACTION);

        // 승인(P2)이 같은 트랜잭션에서 반드시 만든다 — 없으면 데이터가 어긋난 것(500), 화면 문제 아님 (ReportStrDraft 와 같음)
        StrReport report = reports.findByDraftId(draftId)
                .orElseThrow(() -> new IllegalStateException("승인된 초안인데 확정 보고서가 없다: " + draftNo));

        accessLog.pdfDownload(draftId);
        byte[] pdf = renderer.render(ReportPdfContent.preview(report));
        return new StrPdfFile(StrReportPdfAccess.fileName(report.getReportNo(), true), pdf);
    }
}
