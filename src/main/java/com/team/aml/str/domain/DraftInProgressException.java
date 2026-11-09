package com.team.aml.str.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.Map;

public class DraftInProgressException extends DomainException {
    public DraftInProgressException(String caseNo){
        super("이 케이스의 STR 초안을 이미 만들고 있습니다: "+caseNo, Map.of("case_no",caseNo));
    }
}
