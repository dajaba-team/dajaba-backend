package com.team.aml.cases.application;

import com.team.aml.cases.application.OpenCasesAfterScore.Outcome;
import com.team.aml.cases.domain.PolicyNotConfiguredException;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배치 잇기 3단계 — 점수 번호로 케이스 생성 요청 (SCRUM-349). DB 없이 — 저장소·요청은 가짜. */
class OpenCasesAfterScoreTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final long SCORE_RUN = 21L;
    private static final long REQUESTER = 4L;

    private final BatchRunRepository batchRuns = mock(BatchRunRepository.class);
    private final RequestOpenCases requestOpenCases = mock(RequestOpenCases.class);
    private final OpenCasesAfterScore chain = new OpenCasesAfterScore(batchRuns, requestOpenCases);

    @Test
    void 정상_성공한_점수의_날짜로_점수_요청자가_점수가_끝난_시각_기준으로_케이스_생성을_요청한다() {
        BatchRun score = succeededScore();
        when(batchRuns.findById(SCORE_RUN)).thenReturn(Optional.of(score));
        when(requestOpenCases.executeUnlessStartedSince(DATE, REQUESTER, score.getFinishedAt())).thenReturn(
                Optional.of(new RequestOpenCases.Accepted(31L, BatchType.OPEN_CASES, DATE, BatchStatus.RUNNING)));

        assertThat(chain.execute(SCORE_RUN)).isEqualTo(Outcome.REQUESTED);
    }

    @Test
    void 경계_점수가_끝난_뒤_케이스_생성이_이미_시작됐으면_같은_메시지라도_건너뛴다() {   // 확인은 케이스 생성 시작 잠금 안에서
        BatchRun score = succeededScore();
        when(batchRuns.findById(SCORE_RUN)).thenReturn(Optional.of(score));
        when(requestOpenCases.executeUnlessStartedSince(DATE, REQUESTER, score.getFinishedAt())).thenReturn(Optional.empty());

        assertThat(chain.execute(SCORE_RUN)).isEqualTo(Outcome.SKIPPED_ALREADY_STARTED);
    }

    @Test
    void 경계_없는_번호_다른_유형_다시_보낼_실패가_남은_점수는_건너뛴다() {
        when(batchRuns.findById(1L)).thenReturn(Optional.empty());
        BatchRun scan = BatchRun.start(BatchType.RULE_SCAN, DATE, REQUESTER);
        scan.succeed(0, 0);
        when(batchRuns.findById(2L)).thenReturn(Optional.of(scan));
        BatchRun retryable = BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER);
        retryable.fail("대상 3 · 점수 2 · 실패 1(다시 보낼 것 1)");
        when(batchRuns.findById(3L)).thenReturn(Optional.of(retryable));

        assertThat(chain.execute(1L)).isEqualTo(Outcome.SKIPPED_NOT_FOUND);
        assertThat(chain.execute(2L)).isEqualTo(Outcome.SKIPPED_NOT_FOUND);
        assertThat(chain.execute(3L)).isEqualTo(Outcome.SKIPPED_NOT_SUCCEEDED);
        verify(requestOpenCases, never()).executeUnlessStartedSince(any(), anyLong(), any());
    }

    @Test
    void 실패_실행_중_정책_없음_같은_시작_거절은_건너뛴다() {
        when(batchRuns.findById(SCORE_RUN)).thenReturn(Optional.of(succeededScore()));
        when(requestOpenCases.executeUnlessStartedSince(eq(DATE), eq(REQUESTER), any()))
                .thenThrow(new BatchRunningException(BatchType.OPEN_CASES, DATE))
                .thenThrow(new PolicyNotConfiguredException("운영 등급 기준 없음"));

        assertThat(chain.execute(SCORE_RUN)).isEqualTo(Outcome.SKIPPED_REJECTED);
        assertThat(chain.execute(SCORE_RUN)).isEqualTo(Outcome.SKIPPED_REJECTED);
    }

    private static BatchRun succeededScore() {
        BatchRun score = BatchRun.start(BatchType.SCORE_REQUEST, DATE, REQUESTER);
        score.succeed(10, 10);
        return score;
    }
}
