package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseTransaction;
import com.team.aml.cases.domain.TransactionDirection;
import com.team.aml.cases.domain.TransactionScope;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 거래 한 줄 (계약서 C3·C4). 계좌는 가린 값과 account_ref 만 — 원본 account_key 없음, 정답 칸 없음. */
public record CaseTransactionResponse(long transactionId, String fromAccountRef, String fromAccountNo,
                                      String toAccountRef, String toAccountNo, LocalDateTime ts,
                                      BigDecimal amountPaid, String currencyPaid, BigDecimal amountReceived,
                                      String currencyReceived, String paymentFormat, TransactionDirection direction,
                                      TransactionScope scope) {

    static CaseTransactionResponse from(CaseTransaction t) {
        return new CaseTransactionResponse(t.transactionId(), AccountDisplay.ref(t.fromAccountKey()),
                AccountDisplay.maskedNo(t.fromAccountKey()), AccountDisplay.ref(t.toAccountKey()),
                AccountDisplay.maskedNo(t.toAccountKey()), t.ts(), t.amountPaid(), t.currencyPaid(),
                t.amountReceived(), t.currencyReceived(), t.paymentFormat(), t.direction(), t.scope());
    }
}
