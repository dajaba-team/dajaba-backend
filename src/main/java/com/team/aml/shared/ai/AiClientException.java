package com.team.aml.shared.ai;

import java.util.Map;

/**
 * AI 서버 호출 실패. GlobalExceptionHandler 가 httpStatus 로 내보내고 code 는 AI 가 준 것 그대로 (레퍼런스 8.2, 규약 6.3).
 * 기본 502. 예외: AI 409 GENERATION_IN_PROGRESS 는 code=DRAFT_IN_PROGRESS·httpStatus=409.
 * 연결 거부가 재시도 후에도 실패하면 AI_UNAVAILABLE(502), 타임아웃은 AI_TIMEOUT(502). (SCRUM-17)
 */
public class AiClientException extends RuntimeException {

    private static final int DEFAULT_STATUS = 502;   // BAD_GATEWAY

    private final String code;
    private final Map<String, Object> details;
    private final int httpStatus;

    public AiClientException(String code, String message) {
        this(code, message, Map.of(), DEFAULT_STATUS);
    }

    public AiClientException(String code, String message, Map<String, Object> details) {
        this(code, message, details, DEFAULT_STATUS);
    }

    public AiClientException(String code, String message, Map<String, Object> details, int httpStatus) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details; }
    public int httpStatus() { return httpStatus; }
}
