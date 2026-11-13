package com.team.aml.detection.api;

import com.team.aml.detection.domain.AlertTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 경보 거래 한 줄 (R3). C3 와 같은 칸 이름 — 계좌는 가린 값과 account_ref 만, 원본 account_key·정답 칸 없음. */
public record AdminAlertTransactionResponse(long transactionId, String fromAccountRef, String fromAccountNo,
                                            String toAccountRef, String toAccountNo, LocalDateTime ts,
                                            BigDecimal amountPaid, String currencyPaid, BigDecimal amountReceived,
                                            String currencyReceived, String paymentFormat) {

    static AdminAlertTransactionResponse from(AlertTransaction t) {
        return new AdminAlertTransactionResponse(t.transactionId(), DetectionAccountDisplay.ref(t.fromAccountKey()),
                DetectionAccountDisplay.maskedNo(t.fromAccountKey()), DetectionAccountDisplay.ref(t.toAccountKey()),
                DetectionAccountDisplay.maskedNo(t.toAccountKey()), t.ts(), t.amountPaid(), t.currencyPaid(),
                t.amountReceived(), t.currencyReceived(), t.paymentFormat());
    }
}
