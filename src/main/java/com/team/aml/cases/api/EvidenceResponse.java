package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseTransactions;

/**
 * 근거 원본 (CASES 명세 API-09, 계약서 C4). source 는 원천 표시 — 원본 파일 행 번호는 SOURCE 가 줄 때만(지금 없음).
 * record_id 는 transactions.transaction_id(= CSV 행 번호), 합성 데이터(IBM HI-Small)라 is_synthetic = true.
 */
public record EvidenceResponse(String caseNo, CaseTransactionResponse transaction, Source source,
                               boolean eligibleForStr) {

    public record Source(String dataset, long recordId, boolean isSynthetic) {
    }

    static final String DATASET = "IBM AML HI-Small";

    static EvidenceResponse from(CaseTransactions.Evidence e) {
        return new EvidenceResponse(e.caseNo(), CaseTransactionResponse.from(e.transaction()),
                new Source(DATASET, e.transaction().transactionId(), true), e.eligibleForStr());
    }
}
