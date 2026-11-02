package com.team.aml.shared.domain;

import java.util.Map;

/**
 * 403 의 부모. 업무 규칙 중 권한 문제(역할·담당자)일 때 쓴다 — 상태·버전 충돌은 DomainException(409).
 * 하위 클래스 이름이 코드가 된다 — NotApproverException → NOT_APPROVER, NotAssigneeException → NOT_ASSIGNEE.
 * URL·역할 단위로 Spring Security 가 막는 403(FORBIDDEN)과는 다르다 (SCRUM-67).
 */
public abstract class ForbiddenException extends DomainException {

    protected ForbiddenException(String message) {
        super(message);
    }

    protected ForbiddenException(String message, Map<String, Object> details) {
        super(message, details);
    }
}
