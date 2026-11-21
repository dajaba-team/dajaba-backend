package com.team.aml.str.api;

import com.team.aml.str.application.RegenerateStrReportPdf;

/**
 * PDF 다시 만들기 응답 (계약서 P5 ✅): 202 {pdf_status: "PENDING"}. 만들기는 뒤에서 — 화면은 S8 을 3초마다 다시 본다.
 * 이름은 전역 SNAKE_CASE 설정이 바꾼다(@JsonProperty 를 붙이지 않는다).
 */
public record RegenerateReportPdfResponse(String pdfStatus) {

    static RegenerateReportPdfResponse from(RegenerateStrReportPdf.Result r) {
        return new RegenerateReportPdfResponse(r.pdfStatus().name());
    }
}
