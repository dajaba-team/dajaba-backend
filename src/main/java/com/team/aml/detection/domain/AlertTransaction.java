package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 경보에 묶인 거래 한 건 (alert_transactions → transactions). 정답 칸(is_laundering·pattern_*)은 읽지 않는다.
 * 계좌는 내부 키 그대로 — 화면에 낼 때 가리는 건 부르는 쪽(cases api) 몫. ts 는 데이터 시각(시간대 없음).
 */
public record AlertTransaction(long transactionId, LocalDateTime ts, String fromAccountKey, String toAccountKey,
                               BigDecimal amountPaid, String currencyPaid, BigDecimal amountReceived,
                               String currencyReceived, String paymentFormat, boolean selfTransfer) {
}
