package com.team.aml.detection.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ML+DL 조사 순위 대조용 경보 한 줄 (SCRUM-376) — 그 날짜 경보의 번호·룰·저장된 점수만.
 *
 * @param alertNo      경보 번호 `ALT-YYYYMMDD-NNNNNN` — AI 응답 `alert_id` 와 맞춰 보는 값
 * @param ruleId       `rules.rule_id`(버전 아님)
 * @param score        `alert_scores.score`, 점수 행이 없으면 null
 * @param modelVersion `alert_scores.model_version`, 점수 행이 없으면 null
 * @param referenceTs  `alerts.reference_ts` (SCRUM-415)
 * @param evidence     근거 거래의 원본 파일·행 전부, 순서 없음(digest 가 정렬) — 근거가 없으면 빈 목록 (SCRUM-415)
 */
public record DlRankingAlertRow(String alertNo, String ruleId, BigDecimal score, String modelVersion,
                                LocalDateTime referenceTs, List<EvidenceSource> evidence) {
}
