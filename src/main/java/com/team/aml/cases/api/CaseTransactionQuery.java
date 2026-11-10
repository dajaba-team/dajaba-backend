package com.team.aml.cases.api;

import com.team.aml.cases.domain.TransactionOrder;
import com.team.aml.cases.domain.TransactionScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** GET /cases/{case_no}/transactions 쿼리 (계약서 C3). scope 기본 EVIDENCE, sort 기본 ts,asc, size ≤ 100. 틀리면 400. */
public record CaseTransactionQuery(TransactionScope scope,
                                   @Pattern(regexp = "(ts|transaction_id),(asc|desc)") String sort,
                                   @Min(0) Integer page,
                                   @Min(1) @Max(100) Integer size) {

    TransactionScope scopeOrDefault() {
        return scope == null ? TransactionScope.EVIDENCE : scope;
    }

    TransactionOrder order() {
        return switch (sort == null ? "ts,asc" : sort) {
            case "ts,desc" -> TransactionOrder.TS_DESC;
            case "transaction_id,asc" -> TransactionOrder.TRANSACTION_ID_ASC;
            case "transaction_id,desc" -> TransactionOrder.TRANSACTION_ID_DESC;
            default -> TransactionOrder.TS_ASC;
        };
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
