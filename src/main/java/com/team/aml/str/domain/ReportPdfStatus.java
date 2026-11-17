package com.team.aml.str.domain;

/** 확정본 PDF 생성 상태 (V015 str_reports.pdf_status). 모의 제출 전에는 없음(null). */
public enum ReportPdfStatus {
    PENDING,   // 제출 커밋 뒤 만드는 중, 또는 다시 만들기 요청됨
    READY,     // 저장소에 파일 있음 (보관 키·SHA-256·템플릿 버전)
    FAILED     // 만들지 못함 — 승인·제출은 그대로, 승인자가 다시 만들기(P5)
}
