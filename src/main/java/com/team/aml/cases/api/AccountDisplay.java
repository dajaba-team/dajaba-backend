package com.team.aml.cases.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 화면 응답의 계좌 (미결 19, 계약서 v0.2 2.4). 원본 account_key({bank_id}_{account_no})는 응답에 넣지 않는다.
 * <ul>
 *   <li>account_no — 표시용 가린 값 {@code 010_****BD30}. 같은 은행·같은 끝 4자리는 구별되지 않으니 식별자로 쓰지 않는다</li>
 *   <li>account_ref — 여러 계좌 구별용 {@code ACR-XXXXXXXXXXXX}. 같은 계좌는 어느 응답에서나 같은 값</li>
 * </ul>
 */
final class AccountDisplay {

    private static final String MASK = "****";
    private static final int VISIBLE_TAIL = 4;
    private static final int REF_HEX_LENGTH = 12;

    private AccountDisplay() {}

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

    /**
     * account_key 의 SHA-256 앞 12자리. 같은 키는 늘 같은 값이라 목록·상세·거래·그래프에서 같은 계좌를 가리킨다.
     * TODO(미결 19): 발급 규칙은 SOURCE·공통 담당이 정한다(CASES 명세 §5). 키 없는 해시라 계좌 목록이 있으면 대조할 수 있다 —
     *  실데이터 전에 서버 비밀값 HMAC 같은 복원 불가 규칙으로 바꾼다. 형식(ACR-…)은 그대로 둔다.
     */
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
