package com.team.aml.str.application;

import com.team.aml.cases.application.ReportCase;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistory;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.cases.domain.CaseHistoryRepository;
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
 *   <li><b>권한 먼저</b>: 확정 보고서가 이미 있으면(= 승인됨) 그 created_by(승인자)와 비교 — 다르면 바로 403 NOT_DRAFT_APPROVER.
 *       created_by 는 승인 때 한 번 정해지고 안 바뀌어서 잠그기 전에 읽어도 된다. 그래서 다른 승인자는 사본이 오래됐어도(409 CONCURRENT_UPDATE)
 *       이미 제출됐어도(409 INVALID_CASE_TRANSITION) 상태 오류가 아니라 "권한 없음"을 먼저 받는다 (#125 리뷰 — 권한 → 상태 순).
 *       보고서가 아직 없으면(승인 전) 여기서는 넘기고 ④가 409 를 낸다</li>
 *   <li>cases.ReportCase — 케이스 잠금 → version 409 CONCURRENT_UPDATE → 현재 초안 아니면 409 DRAFT_SUPERSEDED
 *       → APPROVED 아니면 409 INVALID_CASE_TRANSITION (이미 REPORTED 면 여기서 409 — 두 번 제출 막기).
 *       str_reports 를 바꾸기 <b>전에</b> 부른다 (ReportCase 주석 "부르는 순서")</li>
 *   <li>잠근 뒤 다시 확인 — ③에서 보고서가 없었는데 그 사이 다른 사람이 승인했을 수 있다. 같은 created_by 비교, 다르면 403(④의 변경도 롤백)</li>
 *   <li>확정 보고서에 제출 기록 — {@link StrReport#markReported}. <b>제출 시각 = ④가 남긴 REPORT 이력의 created_at</b>(같은 값 하나) —
 *       이력과 reported_at 이 따로 찍히면 자정 근처에서 이력은 23:59:59.99, reported_at 은 다음 날 00:00 이 되어
 *       late 가 이력 시각과 어긋날 수 있다 (#125 리뷰). 승인(P2)이 승인 기록 created_at 하나를 쓰는 것과 같은 방식</li>
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
    private final CaseHistoryRepository histories;
    private final CurrentUser currentUser;

    public ReportStrDraft(StrDraftRepository drafts, StrReportRepository reports, ReportCase reportCase,
                          CaseRepository cases, CaseHistoryRepository histories, CurrentUser currentUser) {
        this.drafts = drafts;
        this.reports = reports;
        this.reportCase = reportCase;
        this.cases = cases;
        this.histories = histories;
        this.currentUser = currentUser;
    }

    /** @param expectedCaseVersion 화면이 읽은 cases.version (case_version) */
    @Transactional
    public Result execute(String draftNo, long expectedCaseVersion) {
        AppUserDetails me = currentUser.require();
        ApprovalGate.requireApprover(Role.valueOf(me.role()));

        long draftId = DraftNumbers.require(draftNo);
        StrDraft draft = drafts.findById(draftId).orElseThrow(() -> new DraftNotFoundException(draftNo));

        reports.findByDraftId(draftId).ifPresent(r -> requireApprovedBy(r, me));            // ③ 권한 먼저

        long caseVersion = reportCase.execute(draft.getCaseId(), draftId, expectedCaseVersion);   // ④

        // 승인(P2)이 같은 트랜잭션에서 반드시 만든다 — 없으면 데이터가 어긋난 것(500), 화면 문제 아님
        StrReport report = reports.findByDraftId(draftId)
                .orElseThrow(() -> new IllegalStateException("승인된 초안인데 확정 보고서가 없다: " + draftNo));
        requireApprovedBy(report, me);                                                        // ⑤ 잠근 뒤 다시

        Instant reportedAt = histories.findFirstByCaseIdAndActionOrderByIdDesc(draft.getCaseId(), CaseHistoryAction.REPORT)
                .map(CaseHistory::getCreatedAt)
                .orElseThrow(() -> new IllegalStateException("ReportCase 가 REPORT 이력을 남기지 않았다: " + draftNo));
        report.markReported(me.userId(), reportedAt);                                         // ⑥
        reports.save(report);

        Case c = cases.findById(draft.getCaseId()).orElseThrow(() -> new CaseNotFoundException(draft.getCaseId()));
        return new Result(c.getCaseNo(), c.getStatus().name(), caseVersion, DisplayNumber.draftNo(draftId),
                report.getReportNo(), report.getReportDueAt(), report.getReportedAt(), report.getLate(),
                report.getPdfStatus());
    }

    /** 확정 보고서를 만든 사람(= 승인자, created_by)이 지금 사용자인가. 아니면 403 NOT_DRAFT_APPROVER. */
    private static void requireApprovedBy(StrReport report, AppUserDetails me) {
        if (!Objects.equals(report.getCreatedBy(), me.userId())) {
            throw new NotDraftApproverException();
        }
    }

    /**
     * 계약서 P4 응답 {case_no, case_status, case_version, draft_no, report_no, report_due_at, reported_at, late, pdf_status}.
     * caseStatus 는 문자열 — str api 는 cases 의 CaseStatus 를 볼 수 없다(ArchUnit).
     */
    public record Result(String caseNo, String caseStatus, long caseVersion, String draftNo, String reportNo,
                         Instant reportDueAt, Instant reportedAt, boolean late, ReportPdfStatus pdfStatus) {}
}
