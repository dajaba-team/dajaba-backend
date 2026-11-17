package com.team.aml.str.application;

import com.team.aml.cases.application.ReportCase;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * STR 모의 제출 (계약서 P4, SCRUM-240). 실제 기관 전송은 없다 — 승인된 보고서를 "제출됨"으로 기록한다.
 * 한 트랜잭션: 케이스 APPROVED → REPORTED + REPORT 이력(cases ReportCase) + 확정 보고서 제출 시각·제출자·늦음·PDF PENDING.
 *
 * <p>순서 (바꾸지 않는다):
 * <ol>
 *   <li>승인자 역할 — 아니면 403 NOT_APPROVER (SecurityConfig 가 POST /approvals/** 를 먼저 막고, 여기는 안전장치. P2·P3 와 같음)</li>
 *   <li>초안 읽기 — 없으면 404 DRAFT_NOT_FOUND</li>
 *   <li>cases.ReportCase — 케이스 잠금 → version 409 CONCURRENT_UPDATE → 현재 초안 아니면 409 DRAFT_SUPERSEDED
 *       → APPROVED 아니면 409 INVALID_CASE_TRANSITION (이미 REPORTED 면 여기서 409 — 두 번 제출 막기).
 *       str_reports 를 바꾸기 <b>전에</b> 부른다 (ReportCase 주석 "부르는 순서")</li>
 *   <li>이 초안을 승인한 사람인가 — 확정 보고서의 created_by(= 승인자, 승인 트랜잭션에서 auditing 이 채움)와 비교.
 *       아니면 403 NOT_DRAFT_APPROVER. ③ 뒤라서 APPROVED 가 보장되고(보고서가 있음), 실패하면 ③의 변경도 롤백된다</li>
 *   <li>확정 보고서(승인 P2 때 만든 한 줄)에 제출 기록 — {@link StrReport#markReported}. 늦음은 마감(승인 때 고정)과 제출 시각으로</li>
 * </ol>
 *
 * <p><b>제출자 범위: 이 초안을 승인한 사람만</b> (11/17 결정, #110 〔확인 필요〕 닫음). 승인한 사람이 보고 책임을 끝까지 진다 —
 * 다른 승인자는 목록·상세는 보지만 제출 버튼이 없다(S2 available_actions). cases ReportCase 는 담당 검사만 안 하고 이 검사는 str 몫.
 *
 * <p>PDF 는 여기서 만들지 않는다 — pdf_status PENDING 으로 두고, 생성(제출 커밋 뒤)·다시 만들기(P5)·받기(S8)는 PDF PR(W5) 몫.
 */
@Service
public class ReportStrDraft {

    private final StrDraftRepository drafts;
    private final StrReportRepository reports;
    private final ReportCase reportCase;
    private final CaseRepository cases;
    private final CurrentUser currentUser;

    public ReportStrDraft(StrDraftRepository drafts, StrReportRepository reports, ReportCase reportCase,
                          CaseRepository cases, CurrentUser currentUser) {
        this.drafts = drafts;
        this.reports = reports;
        this.reportCase = reportCase;
        this.cases = cases;
        this.currentUser = currentUser;
    }

    /** @param expectedCaseVersion 화면이 읽은 cases.version (case_version) */
    @Transactional
    public Result execute(String draftNo, long expectedCaseVersion) {
        AppUserDetails me = currentUser.require();
        ApprovalGate.requireApprover(Role.valueOf(me.role()));

        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));

        long caseVersion = reportCase.execute(draft.getCaseId(), draftId, expectedCaseVersion);

        // 승인(P2)이 같은 트랜잭션에서 반드시 만든다 — 없으면 데이터가 어긋난 것(500), 화면 문제 아님
        StrReport report = reports.findByDraftId(draftId)
                .orElseThrow(() -> new IllegalStateException("승인된 초안인데 확정 보고서가 없다: " + draftNo));
        if (!Objects.equals(report.getCreatedBy(), me.userId())) {
            throw new NotDraftApproverException();
        }
        report.markReported(me.userId(), Instant.now());
        reports.save(report);

        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        return new Result(c.getCaseNo(), c.getStatus().name(), caseVersion, DisplayNumber.draftNo(draftId),
                report.getReportNo(), report.getReportDueAt(), report.getReportedAt(), report.getLate(),
                report.getPdfStatus());
    }

    /**
     * 계약서 P4 응답 {case_no, case_status, case_version, draft_no, report_no, report_due_at, reported_at, late, pdf_status}.
     * caseStatus 는 문자열 — str api 는 cases 의 CaseStatus 를 볼 수 없다(ArchUnit).
     */
    public record Result(String caseNo, String caseStatus, long caseVersion, String draftNo, String reportNo,
                         Instant reportDueAt, Instant reportedAt, boolean late, ReportPdfStatus pdfStatus) {}
}
