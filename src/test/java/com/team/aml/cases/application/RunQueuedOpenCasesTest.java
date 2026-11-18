package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 — 큐로 받은 batch_run_id 를 언제 돌리고 언제 건너뛰는지 (SCRUM-276). */
class RunQueuedOpenCasesTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 3);

    private final BatchRunRepository batchRuns = mock(BatchRunRepository.class);
    private final OpenCasesFromAlerts openCases = mock(OpenCasesFromAlerts.class);
    private final RunQueuedOpenCases runQueued = new RunQueuedOpenCases(batchRuns, openCases);

    @Test
    void 정상_RUNNING인_OPEN_CASES면_기존_배치를_그_날짜로_돌린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.of(BatchRun.start(BatchType.OPEN_CASES, DATE, 1L)));

        assertThat(runQueued.execute(7L)).isEqualTo(RunQueuedOpenCases.Outcome.RAN);
        verify(openCases).run(7L, DATE);
    }

    @Test
    void 경계_이미_끝난_배치가_다시_오면_건너뛴다() {
        BatchRun done = BatchRun.start(BatchType.OPEN_CASES, DATE, 1L);
        done.succeed(3, 1);
        when(batchRuns.findById(7L)).thenReturn(Optional.of(done));

        assertThat(runQueued.execute(7L)).isEqualTo(RunQueuedOpenCases.Outcome.SKIPPED_NOT_RUNNING);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 실패_없는_id는_버린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.empty());

        assertThat(runQueued.execute(7L)).isEqualTo(RunQueuedOpenCases.Outcome.SKIPPED_NOT_FOUND);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 실패_다른_배치_유형은_버린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.of(BatchRun.start(BatchType.SCORE_REQUEST, DATE, 1L)));

        assertThat(runQueued.execute(7L)).isEqualTo(RunQueuedOpenCases.Outcome.SKIPPED_WRONG_TYPE);
        verify(openCases, never()).run(anyLong(), any());
    }
}
