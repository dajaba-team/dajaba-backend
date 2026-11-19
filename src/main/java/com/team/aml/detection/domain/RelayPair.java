package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 중계 쌍 하나 — 받은 거래 a(받은 쪽 통화·금액)와 보낸 거래 b(보낸 쪽 통화·금액). rules.md 1절 4 금액은 방향대로.
 * inAccount = a 의 받은 계좌, outAccount = b 의 보낸 계좌 (둘 다 대상 계좌 M 이어야 한다).
 */
public record RelayPair(long inId, LocalDateTime inTs, String inAccount, String inCurrency, BigDecimal inAmount,
                        long outId, LocalDateTime outTs, String outAccount, String outCurrency, BigDecimal outAmount) {
}
