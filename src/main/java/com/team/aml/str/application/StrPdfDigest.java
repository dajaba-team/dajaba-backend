package com.team.aml.str.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** PDF 바이트의 SHA-256 (소문자 hex 64자 — V015 pdf_sha256 CHECK). 보관할 때 남기고, 내려줄 때 다시 맞춰 본다. */
final class StrPdfDigest {

    private StrPdfDigest() {}

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);   // JDK 필수 알고리즘이라 오지 않는다
        }
    }
}
