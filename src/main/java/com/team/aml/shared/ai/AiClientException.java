package com.team.aml.shared.ai;

import java.util.Map;

/**
 * AI 서버 호출 실패. GlobalExceptionHandler 가 502 로 바꾸고 code 는 AI 가 준 것 그대로 (레퍼런스 8.2, 규약 6.3).
 * 연결 자체가 안 될 때는 AI_UNAVAILABLE.
 */
public class AiClientException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public AiClientException(String code, String message) {
        this(code, message, Map.of());
    }

    public AiClientException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
