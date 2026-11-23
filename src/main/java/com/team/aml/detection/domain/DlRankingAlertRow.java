package com.team.aml.detection.domain;

import java.math.BigDecimal;

/**
 * ML+DL 조사 순위 대조용 경보 한 줄 (SCRUM-376) — 그 날짜 경보의 번호·룰·저장된 점수만.
 *
 * @param alertNo      경보 번호 `ALT-YYYYMMDD-NNNNNN` — AI 응답 `alert_id` 와 맞춰 보는 값
 * @param ruleId       `rules.rule_id`(버전 아님)
 * @param score        `alert_scores.score`, 점수 행이 없으면 null
 * @param modelVersion `alert_scores.model_version`, 점수 행이 없으면 null
 */
public record DlRankingAlertRow(String alertNo, String ruleId, BigDecimal score, String modelVersion) {
}
