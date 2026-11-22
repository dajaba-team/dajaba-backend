package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 같은 법인의 이전 경보 한 건 (CASES 상세 prior_alerts, SCRUM-239). cases GetCaseDetail 이 FindAlertsByEntity 로 받는다.
 * 고르는 기준은 prior_alert_count 와 같다 — 같은 entity_id, reference_ts 가 기준 시각보다 앞섬(같은 시각·자기 자신 제외).
 * 정답 칸(is_laundering·pattern_*)은 없다. accountKey 는 내부 키라 화면에는 cases 가 가린 값으로 바꿔 보낸다.
 *
 * @param alertId     내부 id — cases 가 케이스 번호를 찾는 데만 쓰고 응답에는 내보내지 않는다
 * @param scoreStatus PENDING · SCORED · FAILED
 * @param score       SCORED 가 아니면 null
 */
public record PriorAlert(long alertId, String alertNo, String ruleId, LocalDate batchDate, LocalDateTime referenceTs,
                         String accountKey, String scoreStatus, BigDecimal score) {
}
