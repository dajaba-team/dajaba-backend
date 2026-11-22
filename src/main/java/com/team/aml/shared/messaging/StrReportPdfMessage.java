package com.team.aml.shared.messaging;

/** 확정본 PDF 생성 작업. 메시지에는 식별자만 담고 보고서 본문·상태는 DB에서 읽는다. */
public record StrReportPdfMessage(long draftId) {
}
