package com.team.aml.detection.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * cases/api/AccountDisplay·str/api/StrAccountDisplay 와 같은 규칙(계약서 2.4, 미결 19). 관리자 경보 점검 응답용.
 * 공통으로 옮길지는 진희·지윤 님과 정한 뒤 — 그때까지 세 곳 결과가 같아야 한다(DetectionAccountDisplayTest 가 같은 값을 본다).
 */
final class DetectionAccountDisplay {

    private static final String MASK = "****";
    private static final int VISIBLE_TAIL = 4;
    private static final int REF_HEX_LENGTH = 12;

    private DetectionAccountDisplay() {}

    /** {@code 010_8000EBD30 → 010_****BD30}. 번호가 4자리 이하면 끝자리도 보여 주지 않는다. */
    static String maskedNo(String accountKey) {
        if (accountKey == null) {
            return null;
        }
        int sep = accountKey.indexOf('_');
        String bank = sep < 0 ? "" : accountKey.substring(0, sep + 1);
        String number = sep < 0 ? accountKey : accountKey.substring(sep + 1);
        String tail = number.length() > VISIBLE_TAIL ? number.substring(number.length() - VISIBLE_TAIL) : "";
        return bank + MASK + tail;
    }

    /** account_key 의 SHA-256 앞 12자리. 같은 키는 케이스·STR·경보 점검 어디서나 같은 값. */
    static String ref(String accountKey) {
        if (accountKey == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountKey.getBytes(StandardCharsets.UTF_8));
            return "ACR-" + HexFormat.of().withUpperCase().formatHex(digest).substring(0, REF_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);   // JDK 필수 알고리즘이라 오지 않는다
        }
    }
}
