package com.team.aml.detection.api;

import com.team.aml.detection.domain.AlertTransactionOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** GET /admin/alerts/{alert_no}/transactions 쿼리 (R3). C3 와 같은 정렬 — 기본 ts,asc, size ≤ 100. 틀리면 400. */
public record AdminAlertTransactionQuery(@Pattern(regexp = "(ts|transaction_id),(asc|desc)") String sort,
                                         @Min(0) Integer page,
                                         @Min(1) @Max(100) Integer size) {

    AlertTransactionOrder order() {
        return switch (sort == null ? "ts,asc" : sort) {
            case "ts,desc" -> AlertTransactionOrder.TS_DESC;
            case "transaction_id,asc" -> AlertTransactionOrder.TRANSACTION_ID_ASC;
            case "transaction_id,desc" -> AlertTransactionOrder.TRANSACTION_ID_DESC;
            default -> AlertTransactionOrder.TS_ASC;
        };
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
