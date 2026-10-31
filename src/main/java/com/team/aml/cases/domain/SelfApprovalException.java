package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

/** 담당 조사역이 자기 케이스를 승인하려 할 때. 409 SELF_APPROVAL. */
public class SelfApprovalException extends DomainException {
    public SelfApprovalException(String caseNo) {
        super("담당자는 자기 케이스를 승인할 수 없습니다: " + caseNo);
    }
}
