package com.team.aml.detection.domain;

import java.time.LocalDateTime;

/** 대상 계좌에 입금이 있었던 분 E 와 그 분·1분 전 창의 서로 다른 송금 계좌 수 (자기 계좌 거래 제외). */
public record FanInMinute(String accountKey, LocalDateTime minute, int sendersNow, int sendersBefore) {
}
