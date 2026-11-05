package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/** 409 CASE_ALREADY_OPENED. 이미 케이스를 만든 경보에 다시 표시하려 함 — 케이스 생성과 같이 롤백된다. */
public class CaseAlreadyOpenedException extends DomainException {

    public CaseAlreadyOpenedException(long alertId) {
        super("이미 케이스가 생성된 경보입니다", Map.of("alert_id", alertId));
    }
}
