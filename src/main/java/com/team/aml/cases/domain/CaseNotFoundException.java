package com.team.aml.cases.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 CASE_NOT_FOUND. */
public class CaseNotFoundException extends NotFoundException {

    public CaseNotFoundException(Long caseId) {
        super("케이스가 없습니다: " + caseId, Map.of("case_id", caseId));
    }
}
