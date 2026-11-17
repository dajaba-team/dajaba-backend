package com.team.aml.str.api;

import com.team.aml.str.application.ReportStrDraft;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 모의 제출 응답 (계약서 P4 ✅):
 * {case_no, case_status: "REPORTED", case_version, draft_no, report_no, report_due_at, reported_at, late, pdf_status}.
 * 시각은 UTC Z 초 단위(계약서 2.4) — DB 에는 마이크로초까지 있고 late 판단도 그 값으로 한다(StrReport.markReported).
 * 이름은 전역 SNAKE_CASE 설정이 바꾼다(@JsonProperty 를 붙이지 않는다).
 */
public record ReportStrDraftResponse(String caseNo, String caseStatus, long caseVersion, String draftNo,
                                     String reportNo, Instant reportDueAt, Instant reportedAt, boolean late,
                                     String pdfStatus) {

    static ReportStrDraftResponse from(ReportStrDraft.Result r) {
        return new ReportStrDraftResponse(r.caseNo(), r.caseStatus(), r.caseVersion(), r.draftNo(), r.reportNo(),
                seconds(r.reportDueAt()), seconds(r.reportedAt()), r.late(),
                r.pdfStatus() == null ? null : r.pdfStatus().name());
    }

    private static Instant seconds(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.SECONDS);
    }
}
