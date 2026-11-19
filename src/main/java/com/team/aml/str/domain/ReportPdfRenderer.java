package com.team.aml.str.domain;

/**
 * 보고서 PDF 만들기 (서식 회의 10/30 결정 2 — 백엔드가 만든다, AI 서버는 서술부 텍스트까지만).
 * 구현은 str.infrastructure (라이브러리·폰트는 거기만 안다). application 은 이 인터페이스만 본다(ArchUnit 계층 규칙).
 */
public interface ReportPdfRenderer {

    /** 이 렌더러가 만드는 모양의 버전 — str_reports.pdf_template_version (1~32자). 모양을 바꾸면 올린다. */
    String templateVersion();

    /**
     * PDF 바이트. 실패하면 런타임 예외 — 확정본 생성은 그걸 받아 pdf_status FAILED 로 남긴다(승인·제출은 그대로).
     * 같은 입력이면 같은 모양이다(파일 바이트는 생성 시각 메타데이터 때문에 다를 수 있다).
     */
    byte[] render(ReportPdfContent content);
}
