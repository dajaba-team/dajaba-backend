package com.team.aml.shared.ai;

import java.time.Instant;

/**
 * AI 호출 기록 한 행 — 테이블 칸 그대로 (SCRUM-134). 본문은 처음부터 없다 (NFR-COM-06).
 * created_at 은 시스템 시각이라 UTC Z·초 단위로 내보낸다 (미결 27, 배치 목록과 같음).
 */
public record AiCallLogView(long id, String endpoint, String requestId, int attempt, Integer statusCode,
                            String errorCode, int durationMs, String aiVersion, String generationId,
                            String targetType, String targetId, Instant createdAt) {
}