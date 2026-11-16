package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 관리자 경보 점검 상세 (R2) = 목록 한 줄 + rule_facts + 점수 세부. 점수가 없으면 점수 세부는 null, 목록은 [].
 *
 * @param reasons                점수 payload 의 reasons 그대로. 없으면 []
 * @param evidenceTransactionIds 점수 payload 그대로(경보 안 거래). 없으면 []
 * @param contextTransactionIds  점수 payload 그대로(경보 밖 참고 거래). 없으면 []
 */
public record AlertReviewDetail(AlertReviewItem item, JsonNode ruleFacts, String featureVersion, String predictionId,
                                Instant producedAt, JsonNode reasons, JsonNode evidenceTransactionIds,
                                JsonNode contextTransactionIds) {
}
