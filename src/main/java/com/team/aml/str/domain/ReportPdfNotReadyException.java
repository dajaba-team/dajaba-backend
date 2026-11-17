package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 REPORT_PDF_NOT_READY — 확정본 PDF가 아직 없음 (S8). details.pdf_status = PENDING·FAILED (03_shared, 계약서 Q7). */
public class ReportPdfNotReadyException extends DomainException {

    public ReportPdfNotReadyException(ReportPdfStatus status) {
        super(status == ReportPdfStatus.FAILED ? "확정본 PDF를 만들지 못했습니다" : "확정본 PDF를 만드는 중입니다",
                Map.of("pdf_status", status.name()));
    }
}
