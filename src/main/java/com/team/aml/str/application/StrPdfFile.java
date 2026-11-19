package com.team.aml.str.application;

import java.util.Objects;

/**
 * 내려보낼 PDF 한 벌 — 파일 이름과 바이트. api 가 Content-Type application/pdf 로 그대로 보낸다.
 *
 * @param fileName 내려받을 때 이름 (STR-2026-000001.pdf, 미리보기는 STR-2026-000001-preview.pdf). 문서번호라 ASCII 만
 */
public record StrPdfFile(String fileName, byte[] bytes) {

    public StrPdfFile {
        Objects.requireNonNull(fileName, "fileName");
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("빈 PDF 는 보내지 않는다");
        }
    }
}
