package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

/** 지금 담당이거나 예전에 담당했던 사람이 그 케이스를 승인·반려하려 할 때. 409 SELF_APPROVAL (미결 32). */
public class SelfApprovalException extends DomainException {
    public SelfApprovalException(String caseNo) {
        super("직접 담당했던 케이스는 승인하거나 반려할 수 없습니다: " + caseNo);
    }
}
