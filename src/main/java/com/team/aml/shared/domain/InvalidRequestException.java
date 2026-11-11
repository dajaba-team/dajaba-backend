package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 400 INVALID_REQUEST — @Valid 로는 못 보는 입력 오류(지금 값과 비교해야 아는 것). details 키는 요청 JSON 이름(snake_case).
 * 예: 역할 변경에서 지금과 같은 역할·관리자에서 바꾸기 → details.role (SCRUM-135 ②). 업무 충돌은 DomainException(409).
 */
public class InvalidRequestException extends RuntimeException {

    private final Map<String, Object> details;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.details = Map.of(field, message);
    }

    public Map<String, Object> details() { return details; }
}
