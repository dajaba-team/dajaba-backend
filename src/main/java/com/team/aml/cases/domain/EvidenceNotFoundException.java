package com.team.aml.cases.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/**
 * 404 EVIDENCE_NOT_FOUND (CASES 명세 API-09). 거래가 없거나 이 케이스의 근거 거래가 아니면 같은 응답 —
 * 거래 id 를 바꿔 넣어 다른 케이스의 근거를 볼 수 없게 한다.
 */
public class EvidenceNotFoundException extends NotFoundException {

    public EvidenceNotFoundException(String caseNo, long transactionId) {
        super("이 케이스의 근거 거래가 아닙니다: " + transactionId,
                Map.of("case_no", caseNo, "transaction_id", transactionId));
    }
}
