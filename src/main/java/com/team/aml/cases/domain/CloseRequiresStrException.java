package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

/**
 * 409 CLOSE_REQUIRES_STR — 초안이 있는 케이스를 POST /cases/{case_no}/close 로 닫으려 했다 (CASES 명세 API-06).
 * 초안 있는 종결은 str 의 close-with-draft 가 초안 SUPERSEDED 와 같은 트랜잭션에서 한다.
 */
public class CloseRequiresStrException extends DomainException {

    public CloseRequiresStrException(String caseNo) {
        super("초안이 있는 케이스는 초안과 함께 종료해야 합니다: " + caseNo, Map.of("case_no", caseNo));
    }
}
