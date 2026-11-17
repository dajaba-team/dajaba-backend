package com.team.aml.str.domain;

import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/**
 * 409 INVALID_CASE_TRANSITION — PDF 다시 만들기(P5)는 FAILED 일 때만 (str.md 13번).
 * 코드는 프론트 목업(approvals.js regeneratePdf)과 맞춘다(11/17 채영 결정) — 클래스 이름 대신 code() 를 덮어쓴다.
 * details: action=REGENERATE_PDF, draft_no(화면 번호), pdf_status(지금 상태).
 */
public class ReportPdfRetryNotAllowedException extends DomainException {

    public ReportPdfRetryNotAllowedException(long draftId, ReportPdfStatus status) {
        super("확정본 PDF를 다시 만들 수 있는 상태가 아닙니다: " + status,
                Map.of("action", "REGENERATE_PDF",
                        "draft_no", DisplayNumber.draftNo(draftId),
                        "pdf_status", status.name()));
    }

    @Override
    public String code() {
        return "INVALID_CASE_TRANSITION";
    }
}
