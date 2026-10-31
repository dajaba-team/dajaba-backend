package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

/** 운영 등급 경계가 없거나 잘못됨. LOW 로 대신하지 않는다. 409 POLICY_NOT_CONFIGURED (명세 §4 신규 제안). */
public class PolicyNotConfiguredException extends DomainException {
    public PolicyNotConfiguredException(String message) {
        super(message);
    }
}
