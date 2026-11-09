package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

/** 409 NO_ACTIVE_RULES — 켜진 룰이 하나도 없다. */
public class NoActiveRulesException extends DomainException {
    public NoActiveRulesException() {
        super("켜진 룰이 없습니다");
    }
}
