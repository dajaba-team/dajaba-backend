package com.team.aml.cases.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 CASE_NOT_FOUND. HTTP 경로는 case_no, str 공개 기능은 내부 id 로 찾는다. */
public class CaseNotFoundException extends NotFoundException {

    public CaseNotFoundException(Long caseId) {
        super("케이스가 없습니다: " + caseId, Map.of("case_id", caseId));
    }

    /** 화면 경로의 케이스 번호로 못 찾았을 때. 내부 id 는 밖으로 내보내지 않는다. */
    public CaseNotFoundException(String caseNo) {
        super("케이스가 없습니다: " + caseNo, Map.of("case_no", caseNo));
    }
}
