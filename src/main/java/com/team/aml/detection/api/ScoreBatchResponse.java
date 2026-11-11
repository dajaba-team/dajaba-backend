package com.team.aml.detection.api;

import com.team.aml.detection.application.ScoreBatchResult;
import com.team.aml.detection.application.ScoreBatchSummary;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * SD 명세 v0.3 §6 응답. status 는 다시 보낼 실패가 남으면 FAILED(그래도 200).
 * retryable = failed 중 다시 보내면 바뀔 수 있는 것(코드 NULL·COMPUTE_ERROR) — 화면 [점수 재처리] 건수(계약서 5.6 B3).
 */
public record ScoreBatchResponse(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status,
                                 int requested, int scored, int failed, int retryable, Chunks chunks,
                                 List<String> modelVersions,
                                 Instant startedAt, Instant finishedAt) {

    public record Chunks(int total, int failed) {}

    static ScoreBatchResponse from(ScoreBatchResult r) {
        BatchRunView run = r.run();
        ScoreBatchSummary s = r.summary();
        return new ScoreBatchResponse(run.batchRunId(), run.batchType(), run.batchDate(), run.status(),
                s.requested(), s.scored(), s.failed(), s.retryableFailed(), new Chunks(s.chunksTotal(), s.chunksFailed()),
                s.modelVersions(), run.startedAt(), run.finishedAt());
    }
}
