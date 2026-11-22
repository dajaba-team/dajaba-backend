package com.team.aml.shared.batch;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배치 상태 조회 응답 (SD 명세 §4). 시각은 시스템 시각이라 UTC Z·초 단위로 내보낸다 (미결 27).
 * summary 는 결과 상세(V019, SCRUM-349) — 룰 스캔 {@code alerts_by_rule}, 점수 {@code requested·scored·failed·retryable·chunks·model_versions}.
 * 적재·케이스 생성, 실행 중, 예외로 끝난 실패는 null.
 */
public record BatchRunView(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status,
                           Instant startedAt, Instant finishedAt, Integer rowsIn, Integer rowsOut,
                           String errorMessage, JsonNode summary, TriggeredBy triggeredBy) {

    public record TriggeredBy(String loginId) {}
}
