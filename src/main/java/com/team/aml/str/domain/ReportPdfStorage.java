package com.team.aml.str.domain;

/**
 * 확정본 PDF 보관소 (서식 회의 10/30 결정 2 — 파일은 저장소에, DB 에는 보관 키·SHA-256·템플릿 버전·생성 상태).
 * 구현은 str.infrastructure — {@code aml.str.pdf.storage} 로 {@code filesystem}(기본) 또는 {@code minio} 를 선택한다.
 * 지우는 메서드는 두지 않는다 — 5년 보관본(법 제5조의4).
 */
public interface ReportPdfStorage {

    /** key 에 bytes 를 쓴다. 같은 key 가 있으면 덮는다(같은 시도 번호로 다시 쓰는 경우뿐). 실패하면 런타임 예외. */
    void put(String key, byte[] bytes);

    /** key 의 바이트. 없으면 런타임 예외 — READY 인데 파일이 없으면 보관 사고라 화면 문제가 아니다(500). */
    byte[] get(String key);

    /**
     * 보관 키 — {문서번호 연도}/{문서번호}-{시도 번호}.pdf (예: 2026/STR-2026-000001-1.pdf, 512자 안).
     * 다시 만들기(P5)는 시도 번호가 달라서 지난 파일을 덮지 않는다.
     */
    static String keyOf(String reportNo, int attempt) {
        if (reportNo == null || !reportNo.matches("STR-[0-9]{4}-[0-9]{6,}")) {
            throw new IllegalArgumentException("문서번호 형식이 아니다: " + reportNo);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상: " + attempt);
        }
        return reportNo.substring(4, 8) + "/" + reportNo + "-" + attempt + ".pdf";
    }
}
