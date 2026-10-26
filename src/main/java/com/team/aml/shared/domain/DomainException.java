package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 업무 규칙 위반의 부모. GlobalExceptionHandler 가 409 로 바꾼다 (레퍼런스 8.2).
 * code 는 하위 클래스 이름을 UPPER_SNAKE 로 — InvalidCaseTransitionException → INVALID_CASE_TRANSITION.
 * 하위 클래스는 생성자에서 message 와 details 만 넘기면 된다.
 */
public abstract class DomainException extends RuntimeException {

    private final Map<String, Object> details;

    protected DomainException(String message) {
        this(message, Map.of());
    }

    protected DomainException(String message, Map<String, Object> details) {
        super(message);
        this.details = details == null ? Map.of() : details;
    }

    /** INVALID_CASE_TRANSITION 같은 코드. 클래스 이름에서 Exception 을 떼고 UPPER_SNAKE 로. */
    public String code() {
        String name = getClass().getSimpleName().replaceAll("Exception$", "");
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }

    public Map<String, Object> details() { return details; }
}
