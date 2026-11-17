package com.team.aml.detection.domain;

import java.time.LocalDateTime;

/**
 * 대상 계좌에 관련 거래가 있었던 분 E 와 그 분·1분 전 창의 서로 다른 상대 계좌 수 (자기 계좌 거래 제외).
 * R02 = 입금 분·송금 계좌 수, R01 = 송금 분·수신 계좌 수.
 */
public record FanMinute(String accountKey, LocalDateTime minute, int countNow, int countBefore) {
}
