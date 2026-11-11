package com.team.aml.detection.api;

import com.team.aml.detection.application.ScoreBatchResult;
import com.team.aml.detection.application.ScoreBatchSummary;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreBatchResponseTest {

    @Test
    void retryable은_다시_보낼_실패만_failed는_전부() {   // 계약서 5.6 B3 — 화면 [점수 재처리] 건수 (#94 리뷰)
        BatchRunView run = new BatchRunView(7L, BatchType.SCORE_REQUEST, LocalDate.of(2022, 9, 1), BatchStatus.FAILED,
                Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2026-11-11T00:00:05Z"), 5, 2, null, null);
        ScoreBatchSummary summary = new ScoreBatchSummary(5, 2, 3, 2, 3, 1, List.of("stub-1"));

        ScoreBatchResponse response = ScoreBatchResponse.from(new ScoreBatchResult(run, summary));

        assertThat(response.failed()).isEqualTo(3);
        assertThat(response.retryable()).isEqualTo(2);
        assertThat(response.chunks()).isEqualTo(new ScoreBatchResponse.Chunks(3, 1));
    }
}
