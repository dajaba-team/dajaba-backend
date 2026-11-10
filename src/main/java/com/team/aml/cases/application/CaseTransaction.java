package com.team.aml.cases.application;

import com.team.aml.cases.domain.TransactionDirection;
import com.team.aml.cases.domain.TransactionScope;
import com.team.aml.detection.domain.AlertTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 케이스 화면의 거래 한 건 (C3·C4). 계좌는 내부 키 — 컨트롤러가 가린 값·account_ref 로 바꾼다(AccountDisplay).
 * selfTransfer 는 합계 계산용이라 화면 응답에는 내지 않는다.
 */
public record CaseTransaction(long transactionId, String fromAccountKey, String toAccountKey, LocalDateTime ts,
                              BigDecimal amountPaid, String currencyPaid, BigDecimal amountReceived,
                              String currencyReceived, String paymentFormat, TransactionDirection direction,
                              TransactionScope scope, boolean selfTransfer) {

    static CaseTransaction of(AlertTransaction t, String caseAccountKey, TransactionScope scope) {
        return new CaseTransaction(t.transactionId(), t.fromAccountKey(), t.toAccountKey(), t.ts(), t.amountPaid(),
                t.currencyPaid(), t.amountReceived(), t.currencyReceived(), t.paymentFormat(),
                TransactionDirection.of(caseAccountKey, t.fromAccountKey(), t.toAccountKey()), scope,
                t.selfTransfer());
    }
}
