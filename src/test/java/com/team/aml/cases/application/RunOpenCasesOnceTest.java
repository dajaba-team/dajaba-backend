package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 — batch_run_id 를 언제 돌리고·건너뛰고·포기 처리하는지 (SCRUM-276, #130 리뷰). */
class RunOpenCasesOnceTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 3);

    private final BatchRunRepository batchRuns = mock(BatchRunRepository.class);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final OpenCasesFromAlerts openCases = mock(OpenCasesFromAlerts.class);
    private final OpenCasesRecorder recorder = mock(OpenCasesRecorder.class);
    private final RunOpenCasesOnce runOnce = new RunOpenCasesOnce(batchRuns, cases, openCases, recorder);

    RunOpenCasesOnceTest() {
        when(cases.tryLockOpenCasesRun(7L)).thenReturn(true);
    }

    @Test
    void 정상_RUNNING인_OPEN_CASES면_기존_배치를_그_날짜로_돌린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.of(BatchRun.start(BatchType.OPEN_CASES, DATE, 1L)));

        assertThat(runOnce.execute(7L)).isEqualTo(RunOpenCasesOnce.Outcome.RAN);
        verify(openCases).run(7L, DATE);
    }

    @Test
    void 경계_다른_곳이_실행_잠금을_쥐고_있으면_상태도_안_보고_건너뛴다() {
        when(cases.tryLockOpenCasesRun(7L)).thenReturn(false);

        assertThat(runOnce.execute(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_CLAIMED);
        verify(batchRuns, never()).findById(anyLong());
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 경계_이미_끝난_배치가_다시_오면_건너뛴다() {
        BatchRun done = BatchRun.start(BatchType.OPEN_CASES, DATE, 1L);
        done.succeed(3, 1);
        when(batchRuns.findById(7L)).thenReturn(Optional.of(done));

        assertThat(runOnce.execute(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_NOT_RUNNING);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 실패_없는_id는_버린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.empty());

        assertThat(runOnce.execute(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_NOT_FOUND);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 실패_다른_배치_유형은_버린다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.of(BatchRun.start(BatchType.SCORE_REQUEST, DATE, 1L)));

        assertThat(runOnce.execute(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_WRONG_TYPE);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 정상_재시도를_다_쓰면_RUNNING_행을_FAILED로_바꿔_다시_요청할_수_있게_한다() {
        when(batchRuns.findById(7L)).thenReturn(Optional.of(BatchRun.start(BatchType.OPEN_CASES, DATE, 1L)));

        assertThat(runOnce.giveUp(7L)).isEqualTo(RunOpenCasesOnce.Outcome.MARKED_FAILED);
        verify(recorder).fail(7L, RunOpenCasesOnce.GAVE_UP_MESSAGE);
    }

    @Test
    void 경계_포기_처리_때_다른_곳이_실행_중이면_건드리지_않는다() {
        when(cases.tryLockOpenCasesRun(7L)).thenReturn(false);

        assertThat(runOnce.giveUp(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_CLAIMED);
        verify(recorder, never()).fail(anyLong(), anyString());
    }

    @Test
    void 경계_포기_처리_때_이미_끝났으면_결과를_덮지_않는다() {
        BatchRun done = BatchRun.start(BatchType.OPEN_CASES, DATE, 1L);
        done.succeed(3, 1);
        when(batchRuns.findById(7L)).thenReturn(Optional.of(done));

        assertThat(runOnce.giveUp(7L)).isEqualTo(RunOpenCasesOnce.Outcome.SKIPPED_NOT_RUNNING);
        verify(recorder, never()).fail(anyLong(), anyString());
    }
}
