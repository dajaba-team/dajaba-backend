package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;
import com.team.aml.user.domain.Role;

/** 승인자가 아닌 역할이 승인·반려하려 할 때. 409 NOT_APPROVER. 1차로는 SecurityConfig가 403으로 막는다. */
public class NotApproverException extends DomainException {
    public NotApproverException(Role role) {
        super("승인·반려는 승인자만 할 수 있습니다. 현재 역할: " + role);
    }
}
