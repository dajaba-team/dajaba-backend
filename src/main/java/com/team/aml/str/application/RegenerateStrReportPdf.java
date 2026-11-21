package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.ApprovalGate;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NotDraftApproverException;
import com.team.aml.str.domain.ReportPdfStatus;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import com.team.aml.user.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 확정본 PDF 다시 만들기 (계약서 P5). 만들지 못한 확정본을 <b>같은 스냅샷으로</b> 다시 만든다 — 최신 점수·고객 정보로
 * 다시 조립하지 않는다(서식 회의 10/30 결정 2). 이 트랜잭션은 → PENDING 만 하고, 만들기는 커밋 뒤 뒤에서(StrReportPdfTrigger).
 *
 * <p>받는 상태: FAILED, 그리고 <b>오래된 PENDING</b>(aml.str.pdf.stale-pending-after, 기본 10분) — 만드는 도중 서버가 죽으면
 * 커밋 뒤 작업이 다시 걸리지 않아 PENDING 에 갇히므로, 이 버튼이 복구 길이다(#119 리뷰 〔후속〕을 여기서 닫음, 11/20 코드 리뷰).
 *
 * <p>순서 (P4 ReportStrDraft 와 같은 "권한 → 상태"):
 * <ol>
 *   <li>승인자 역할 — 아니면 403 NOT_APPROVER (SecurityConfig 가 POST /approvals/** 를 먼저 막고, 여기는 안전장치)</li>
 *   <li>초안 404 → 확정 보고서가 있으면 {@link StrReport#isApprovedBy}, 아니면 403 NOT_DRAFT_APPROVER —
 *       <b>이 초안을 승인한 사람만</b>(P4 제출자 범위 11/17 결정과 같은 규칙, 같은 메서드)</li>
 *   <li>대체된 초안 409 DRAFT_SUPERSEDED → 케이스 REPORTED 가 아니면 409 INVALID_CASE_TRANSITION(action REGENERATE_PDF)</li>
 *   <li>{@link StrReport#retryPdf} — 다시 만들 수 없는 상태면 409 INVALID_CASE_TRANSITION(details.action REGENERATE_PDF·draft_no·pdf_status,
 *       프론트 목업과 같음 11/17). 두 사람이 동시에 누르면 하나는 @Version 으로 409 CONCURRENT_UPDATE</li>
 * </ol>
 * 버튼(REGENERATE_PDF)은 같은 두 규칙을 {@link StrReportPdfAccess#canRegenerate} 로 묶어 본다.
 *
 * <p>기록: 실패 코드 칸은 PENDING 이면 비워야 해서(V015 CHECK) 직전 실패 이유가 DB 에서 지워진다 — 그래서 누가·언제·무슨 이유로
 * 다시 만들었는지 INFO 로그 한 줄로 남긴다(초안 번호·사용자 id·직전 실패 코드·시도 수, 본문·계좌 없음). 이력 테이블에 남기는 것은
 * 마이그레이션이나 cases 이력 동작 추가가 필요해 팀 결정 뒤 후속.
 *
 * <p>응답 202 {pdf_status: "PENDING"} 은 "받았다"는 뜻이다 — 커밋 전에 만든 값이라, 커밋 직후 실행기가 거절해 FAILED(NOT_STARTED)가 되면
 * 다음 S8 이 409 REPORT_PDF_NOT_READY(FAILED)로 알려 준다.
 */
@Service
public class RegenerateStrReportPdf {

    private static final Logger log = LoggerFactory.getLogger(RegenerateStrReportPdf.class);

    static final String ACTION = "REGENERATE_PDF";

    private final StrDraftRepository drafts;
    private final CaseRepository cases;
    private final StrReportRepository reports;
    private final StrReportPdfTrigger pdfTrigger;
    private final CurrentUser currentUser;
    private final Duration staleAfter;

    public RegenerateStrReportPdf(StrDraftRepository drafts, CaseRepository cases, StrReportRepository reports,
                                  StrReportPdfTrigger pdfTrigger, CurrentUser currentUser,
                                  @Value(StrReportPdfAccess.STALE_PENDING_AFTER) Duration staleAfter) {
        this.drafts = drafts;
        this.cases = cases;
        this.reports = reports;
        this.pdfTrigger = pdfTrigger;
        this.currentUser = currentUser;
        this.staleAfter = staleAfter;
    }

    @Transactional
    public Result execute(String draftNo) {
        AppUserDetails me = currentUser.require();
        ApprovalGate.requireApprover(Role.valueOf(me.role()));

        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));
        Optional<StrReport> found = reports.findByDraftId(draftId);
        if (found.isPresent() && !found.get().isApprovedBy(me.userId())) {                // 권한 먼저
            throw new NotDraftApproverException();
        }

        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        StrReportPdfAccess.requireCurrentDraft(draft, c);
        StrReportPdfAccess.requireStatus(c, CaseStatus.REPORTED, ACTION);

        StrReport report = found
                .orElseThrow(() -> new IllegalStateException("제출된 초안인데 확정 보고서가 없다: " + draftNo));
        String reason = report.retryPdf(Instant.now(), staleAfter);                       // → PENDING, 아니면 409
        reports.save(report);
        pdfTrigger.afterCommit(draftId);
        log.info("확정본 PDF 다시 만들기 draft_no={} user_id={} previous={} attempts={}",
                DisplayNumber.draftNo(draftId), me.userId(), reason, report.getPdfAttempts());
        return new Result(DisplayNumber.draftNo(draftId), report.getPdfStatus());
    }

    /** 계약서 P5 응답은 pdf_status 하나 — draftNo 는 로그·테스트용으로만 든다. */
    public record Result(String draftNo, ReportPdfStatus pdfStatus) {}
}
