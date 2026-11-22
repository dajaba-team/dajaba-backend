package com.team.aml.detection.application;

import com.team.aml.detection.application.ScoreAfterRuleScan.Outcome;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배치 잇기 2단계 — 룰 스캔 번호로 점수 (SCRUM-349). DB 없이 — 저장소·점수·기록은 가짜, 기다림은 기록만. */
class ScoreAfterRuleScanTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final long SCAN_RUN = 11L;
    private static final long SCORE_RUN = 21L;
    private static final long REQUESTER = 4L;

    private final BatchRunRepository batchRuns = mock(BatchRunRepository.class);
    private final RequestScores scores = mock(RequestScores.class);
    private final ScoreBatchRecorder recorder = mock(ScoreBatchRecorder.class);
    private final List<Duration> waits = new ArrayList<>();
    private final ScoreAfterRuleScan chain = new ScoreAfterRuleScan(batchRuns, scores, recorder, waits::add);

    @Test
    void 정상_성공한_룰_스캔의_날짜로_룰_스캔_요청자가_점수를_돌린다() {
        BatchRun scan = succeededScan();
        when(batchRuns.findById(SCAN_RUN)).thenReturn(Optional.of(scan));
        when(scores.startUnlessStartedSince(DATE, REQUESTER, scan.getFinishedAt())).thenReturn(OptionalLong.of(SCORE_RUN));

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.RAN);
        verify(scores).run(SCORE_RUN, DATE);
    }

    @Test
    void 경계_룰_스캔이_끝난_뒤_점수가_이미_시작됐으면_같은_메시지라도_건너뛴다() {   // 확인은 점수 시작 잠금 안에서
        BatchRun scan = succeededScan();
        when(batchRuns.findById(SCAN_RUN)).thenReturn(Optional.of(scan));
        when(scores.startUnlessStartedSince(DATE, REQUESTER, scan.getFinishedAt())).thenReturn(OptionalLong.empty());

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.SKIPPED_ALREADY_STARTED);
        verify(scores, never()).run(anyLong(), any());
    }

    @Test
    void 경계_없는_번호_다른_유형_실패한_룰_스캔은_건너뛴다() {
        when(batchRuns.findById(1L)).thenReturn(Optional.empty());
        BatchRun score = BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER);
        score.succeed(0, 0);
        when(batchRuns.findById(2L)).thenReturn(Optional.of(score));
        BatchRun failed = BatchRun.start(BatchType.RULE_SCAN, DATE, REQUESTER);
        failed.fail("INVALID_RULE_PARAMS");
        when(batchRuns.findById(3L)).thenReturn(Optional.of(failed));

        assertThat(chain.execute(1L)).isEqualTo(Outcome.SKIPPED_NOT_FOUND);
        assertThat(chain.execute(2L)).isEqualTo(Outcome.SKIPPED_NOT_FOUND);
        assertThat(chain.execute(3L)).isEqualTo(Outcome.SKIPPED_NOT_SUCCEEDED);
        verify(scores, never()).startUnlessStartedSince(any(), anyLong(), any());
    }

    @Test
    void 실패_같은_날짜_점수가_실행_중이면_409를_건너뛴다() {
        when(batchRuns.findById(SCAN_RUN)).thenReturn(Optional.of(succeededScan()));
        when(scores.startUnlessStartedSince(eq(DATE), eq(REQUESTER), any()))
                .thenThrow(new BatchRunningException(BatchType.SCORE_REQUEST, DATE));

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.SKIPPED_REJECTED);
        verify(scores, never()).run(anyLong(), any());
    }

    @Test
    void 실패_점수가_실패하고_FAILED도_남았으면_더_하지_않는다() {   // RequestScores 가 FAILED 를 남긴 보통의 경우
        startedScore();
        when(scores.run(SCORE_RUN, DATE)).thenThrow(new IllegalStateException("db down"));
        BatchRun failedScore = BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER);
        failedScore.fail("점수 배치 실패 (IllegalStateException)");
        when(batchRuns.findById(SCORE_RUN)).thenReturn(Optional.of(failedScore));

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.RUN_FAILED);
        verify(recorder, never()).fail(anyLong(), anyString());
        assertThat(waits).isEmpty();
    }

    @Test
    void 실패_FAILED_기록까지_실패해_RUNNING으로_남았으면_간격을_두고_다시_기록한다() {   // 리뷰 — ACK 뒤 그 날짜가 409 로 막히지 않게
        startedScore();
        when(scores.run(SCORE_RUN, DATE)).thenThrow(new IllegalStateException("db down"));
        when(batchRuns.findById(SCORE_RUN))
                .thenThrow(new IllegalStateException("db still down"))
                .thenReturn(Optional.of(BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER)));   // 두 번째엔 DB 돌아옴, RUNNING

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.RUN_FAILED);
        verify(recorder).fail(SCORE_RUN, ScoreAfterRuleScan.STUCK_MESSAGE);
        assertThat(waits).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 실패_다시_기록해도_끝까지_안_되면_세_번_기다린_뒤_로그만_남기고_예외는_없다() {
        startedScore();
        when(scores.run(SCORE_RUN, DATE)).thenThrow(new IllegalStateException("db down"));
        when(batchRuns.findById(SCORE_RUN)).thenReturn(Optional.of(BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER)));
        doThrow(new IllegalStateException("db still down")).when(recorder).fail(eq(SCORE_RUN), anyString());

        assertThat(chain.execute(SCAN_RUN)).isEqualTo(Outcome.RUN_FAILED);
        verify(recorder, times(4)).fail(eq(SCORE_RUN), anyString());
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    private void startedScore() {
        BatchRun scan = succeededScan();
        when(batchRuns.findById(SCAN_RUN)).thenReturn(Optional.of(scan));
        when(scores.startUnlessStartedSince(DATE, REQUESTER, scan.getFinishedAt())).thenReturn(OptionalLong.of(SCORE_RUN));
    }

    private static BatchRun succeededScan() {
        BatchRun scan = BatchRun.start(BatchType.RULE_SCAN, DATE, REQUESTER);
        scan.succeed(100, 3);
        return scan;
    }
}
