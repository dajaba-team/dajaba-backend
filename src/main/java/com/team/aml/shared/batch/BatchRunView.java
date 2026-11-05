package com.team.aml.shared.batch;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배치 상태 조회 응답 (SD 명세 §4). 시각은 시스템 시각이라 UTC Z·초 단위로 내보낸다 (미결 27).
 * metrics 는 batch_runs 에 칸이 생기면 붙인다 (미결 4).
 */
public record BatchRunView(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status,
                           Instant startedAt, Instant finishedAt, Integer rowsIn, Integer rowsOut,
                           String errorMessage, TriggeredBy triggeredBy) {

    public record TriggeredBy(String loginId) {}
}
