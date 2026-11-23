package com.team.aml.str.domain;

/** 확정본 PDF 저장소가 일시적으로 응답하지 않을 때 던진다(503). */
public class ReportPdfStorageUnavailableException extends RuntimeException {

    public ReportPdfStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}