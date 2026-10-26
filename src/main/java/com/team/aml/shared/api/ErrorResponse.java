package com.team.aml.shared.api;

import java.util.Map;

/**
 * 프론트가 받는 에러는 이 모양 하나 (레퍼런스 8.2, 규약 6절과 같은 형식).
 * { "error": { "code": "...", "message": "...", "details": {...} } }
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, Map<String, Object> details) {
        public Body {
            if (details == null) details = Map.of();
        }
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Body(code, message, Map.of()));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new Body(code, message, details));
    }
}
