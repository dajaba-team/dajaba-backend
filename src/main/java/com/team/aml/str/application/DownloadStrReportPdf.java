package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.ReportPdfStorage;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import com.team.aml.user.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정본 PDF 받기 (계약서 S8). 모의 제출 뒤 보관된 파일을 그대로 준다 — 다시 그리지 않는다(템플릿이 바뀌어도 지난 보고서는 그대로, 결정 2).
 *
 * <p>순서: 초안 404 → 담당 조사역·승인자가 아니면 403 NOT_ASSIGNEE → 대체된 초안 409 DRAFT_SUPERSEDED
 * → 케이스 REPORTED 가 아니면 409 INVALID_CASE_TRANSITION(action DOWNLOAD_PDF — 제출 전엔 S7 미리보기)
 * → READY 가 아니면 409 REPORT_PDF_NOT_READY(details.pdf_status PENDING·FAILED — 화면은 PENDING 이면 3초마다 다시, FAILED 면 승인자에게 P5)
 * → 보관본 읽기 + SHA-256 대조 → 열람 기록 PDF_DOWNLOAD.
 *
 * <p>보관본이 없거나 해시가 다르면 500 — 보관 사고라 화면이 고칠 수 있는 일이 아니다(로그에 초안 번호만).
 * 해시를 맞춰 보는 이유: DB 의 pdf_sha256 이 "무엇을 보관했는지"의 근거라, 바뀐 파일을 확정본으로 내보내지 않는다.
 */
@Service
public class DownloadStrReportPdf {

    static final String ACTION = "DOWNLOAD_PDF";

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final StrReportRepository reports;
    private final ReportPdfStorage storage;
    private final CurrentUser currentUser;
    private final AccessLogWriter accessLog;

    public DownloadStrReportPdf(StrDraftRepository drafts, CaseRepository cases, StrReportRepository reports,
                                ReportPdfStorage storage, CurrentUser currentUser, AccessLogWriter accessLog) {
        this.drafts = drafts;
        this.cases = cases;
        this.reports = reports;
        this.storage = storage;
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
        StrReportPdfAccess.requireStatus(c, CaseStatus.REPORTED, ACTION);

        StrReport report = reports.findByDraftId(draftId)
                .orElseThrow(() -> new IllegalStateException("제출된 초안인데 확정 보고서가 없다: " + draftNo));
        report.requirePdfReady();                                                   // 409 REPORT_PDF_NOT_READY

        byte[] pdf = storage.get(report.getPdfStorageKey());
        if (!StrPdfDigest.sha256(pdf).equals(report.getPdfSha256())) {
            throw new IllegalStateException("확정본 보관본의 SHA-256 이 기록과 다르다: " + draftNo);
        }
        accessLog.pdfDownload(draftId);
        return new StrPdfFile(StrReportPdfAccess.fileName(report.getReportNo(), false), pdf);
    }
}
