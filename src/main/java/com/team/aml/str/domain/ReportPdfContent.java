package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * PDF 한 벌의 재료 — 확정 보고서(str_reports)에 고정된 값만 쓴다 (서식 회의 10/30 결정 2).
 * 최신 점수·바뀐 고객 정보로 다시 조립하지 않는다 — 스냅샷이 승인 때 고정한 것 그대로.
 *
 * <ul>
 *   <li>{@link #preview} — 제출 전 미리보기(S7). 보고일자가 아직 없고 "제출 전" 표시. 저장하지 않는다</li>
 *   <li>{@link #submitted} — 확정본(S8). 모의 제출 뒤 보고일자(reported_at)·늦음을 넣는다</li>
 * </ul>
 *
 * @param reportNo        표제부 1 문서번호 (STR-2026-000001)
 * @param reportedAt      표제부 2 보고일자. 미리보기면 null
 * @param reportDueAt     보고 마감 (내부 결재 정보 칸)
 * @param late            마감 뒤 제출인가. 미리보기면 null
 * @param formSnapshot    str_reports.form_snapshot (StrReportForm 모양)
 * @param snapshotVersion str_reports.snapshot_version (form_v1)
 * @param preview         true = 제출 전 미리보기
 */
public record ReportPdfContent(String reportNo, Instant reportedAt, Instant reportDueAt, Boolean late,
                               JsonNode formSnapshot, String snapshotVersion, boolean preview) {

    public ReportPdfContent {
        Objects.requireNonNull(reportNo, "reportNo");
        Objects.requireNonNull(reportDueAt, "reportDueAt");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion");
        if (formSnapshot == null || !formSnapshot.isObject()) {
            throw new IllegalArgumentException("서식 스냅샷은 JSON 객체여야 한다");
        }
        if (!preview && (reportedAt == null || late == null)) {
            throw new IllegalArgumentException("확정본은 보고일자·늦음이 있어야 한다 — 모의 제출 뒤에 만든다");
        }
        if (preview && reportedAt != null) {
            throw new IllegalArgumentException("미리보기는 보고일자를 넣지 않는다");
        }
        formSnapshot = formSnapshot.deepCopy();
    }

    /** 제출 전 미리보기(S7). 승인된 보고서면 된다 — 제출 여부는 부르는 쪽(케이스 APPROVED)이 본다. */
    public static ReportPdfContent preview(StrReport report) {
        return new ReportPdfContent(report.getReportNo(), null, report.getReportDueAt(), null,
                report.getFormSnapshot(), report.getSnapshotVersion(), true);
    }

    /** 확정본(S8·P5). 모의 제출 전이면 IllegalStateException — 부르는 쪽 버그. */
    public static ReportPdfContent submitted(StrReport report) {
        if (!report.isReported()) {
            throw new IllegalStateException("모의 제출 전 보고서로 확정본을 만들 수 없다: " + report.getReportNo());
        }
        return new ReportPdfContent(report.getReportNo(), report.getReportedAt(), report.getReportDueAt(),
                report.getLate(), report.getFormSnapshot(), report.getSnapshotVersion(), false);
    }

    @Override
    public JsonNode formSnapshot() {
        return formSnapshot.deepCopy();
    }
}
