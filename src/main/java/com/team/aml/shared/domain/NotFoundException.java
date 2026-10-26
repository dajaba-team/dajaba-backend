package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 404 의 부모. 하위 클래스 이름이 코드가 된다 — CaseNotFoundException → CASE_NOT_FOUND.
 */
public abstract class NotFoundException extends DomainException {
    protected NotFoundException(String message, Map<String, Object> details) {
        super(message, details);
    }
}
