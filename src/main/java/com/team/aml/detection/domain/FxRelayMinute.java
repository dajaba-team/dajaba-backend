package com.team.aml.detection.domain;

import java.time.LocalDateTime;

/** 중계 쌍을 완성하는 출금이 있었던 (계좌 M, 분 E) 와 1분 전 창 [E-H, E) 에도 쌍이 있었는지. */
public record FxRelayMinute(String accountKey, LocalDateTime minute, boolean pairBefore) {
}
