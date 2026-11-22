package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertScoreRepository;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchProgress;
import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 룰 스캔·점수 화면 요청이 202 로 바뀐 부분 (SCRUM-349) — DB 없이. 적재(RequestIbmDatasetLoad)와 같은 모양인지:
 * 접수 상태는 실행기에 넘기기 전에 읽고, 거절(409)은 바로, 실행기가 거절하면 FAILED 를 남기고 다시 던진다.
 * 실제 DB·HTTP 한 바퀴는 DatasetLoadApiTest, summary 내용은 RunRuleScanTest·RequestScoresTest.
 */
class AsyncBatchRequestTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 3);
    private static final BatchRunView RUNNING_SCAN = view(7L, BatchType.RULE_SCAN);
    private static final BatchRunView RUNNING_SCORE = view(8L, BatchType.SCORE_REQUEST);

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final BatchRunQuery batchRuns = mock(BatchRunQuery.class);
    private final BatchChain chain = mock(BatchChain.class);
    private final BatchProgress progress = mock(BatchProgress.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final TaskExecutor capturing = submitted::add;
    private final TaskExecutor rejecting = task -> { throw new TaskRejectedException("full"); };

    private final RuleScanRecorder scanRecorder = mock(RuleScanRecorder.class);
    private final RuleScanner scanner = mock(RuleScanner.class);
    private final ScoreBatchRecorder scoreRecorder = mock(ScoreBatchRecorder.class);
    private final AlertScoreRepository scores = mock(AlertScoreRepository.class);

    AsyncBatchRequestTest() {
        when(currentUser.require()).thenReturn(new AppUserDetails(1L, "admin", "관리자", "ADMIN"));
        when(batchRuns.get(7L)).thenReturn(RUNNING_SCAN);
        when(batchRuns.get(8L)).thenReturn(RUNNING_SCORE);
        when(scanRecorder.start(DATE, 1L)).thenReturn(7L);
        when(scoreRecorder.start(DATE, 1L)).thenReturn(8L);
    }

    @Test
    void 룰_스캔은_RUNNING을_돌려주고_스캔은_실행기에서_돈다() {
        RunRuleScan sut = ruleScan(capturing);

        assertThat(sut.execute(DATE)).isEqualTo(RUNNING_SCAN);
        verify(scanner, never()).scan(anyLong(), eq(DATE));   // 아직 안 돎

        submitted.get(0).run();
        verify(scanner).scan(7L, DATE);
        verify(chain).afterRuleScan(7L);
        verify(progress).clear(7L);   // 진행 단계는 끝나면 지움
    }

    @Test
    void 룰_스캔이_실행기에서_실패해도_예외가_스레드_밖으로_안_나가고_FAILED는_run이_남긴다() {
        when(scanner.scan(7L, DATE)).thenThrow(new IllegalStateException("db down"));
        RunRuleScan sut = ruleScan(capturing);
        sut.execute(DATE);

        assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
        verify(scanRecorder).fail(7L, "룰 스캔 실패 (IllegalStateException)");
        verify(chain, never()).afterRuleScan(anyLong());
        verify(progress).clear(7L);   // 실패해도 지움
    }

    @Test
    void 룰_스캔_실행기가_거절하면_FAILED로_남기고_다시_던진다() {   // RUNNING 이 남아 그 날짜가 막히지 않게
        RunRuleScan sut = ruleScan(rejecting);

        assertThatThrownBy(() -> sut.execute(DATE)).isInstanceOf(TaskRejectedException.class);
        verify(scanRecorder).fail(7L, "룰 스캔 작업을 시작하지 못함 (TaskRejectedException)");
    }

    @Test
    void 룰_스캔_거절은_실행기에_넘기지_않고_바로_409() {
        when(scanRecorder.start(DATE, 1L)).thenThrow(new BatchAlreadyDoneException(BatchType.RULE_SCAN, DATE));
        RunRuleScan sut = ruleScan(capturing);

        assertThatThrownBy(() -> sut.execute(DATE)).isInstanceOf(BatchAlreadyDoneException.class);
        assertThat(submitted).isEmpty();
    }

    @Test
    void 점수는_RUNNING을_돌려주고_점수는_실행기에서_돈다() {
        RequestScores sut = requestScores(capturing);

        assertThat(sut.execute(DATE)).isEqualTo(RUNNING_SCORE);
        verify(scoreRecorder, never()).finish(anyLong(), org.mockito.ArgumentMatchers.any());

        submitted.get(0).run();   // 대상 0건 — AI 를 안 부르고 끝
        verify(scoreRecorder).finish(eq(8L), eq(new ScoreBatchSummary(0, 0, 0, 0, 0, 0, List.of())));
        verify(chain).afterScore(8L, BatchStatus.RUNNING);   // 상태는 batchRuns.get 이 준 값(여기선 mock)
    }

    @Test
    void 점수가_실행기에서_실패해도_예외가_스레드_밖으로_안_나가고_FAILED는_run이_남긴다() {
        when(scores.findScoreTargets(eq(DATE), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        RequestScores sut = requestScores(capturing);
        sut.execute(DATE);

        assertThatCode(() -> submitted.get(0).run()).doesNotThrowAnyException();
        verify(scoreRecorder).fail(eq(8L), anyString());
    }

    @Test
    void 점수_실행기가_거절하면_FAILED로_남기고_다시_던진다() {
        RequestScores sut = requestScores(rejecting);

        assertThatThrownBy(() -> sut.execute(DATE)).isInstanceOf(TaskRejectedException.class);
        verify(scoreRecorder).fail(8L, "점수 작업을 시작하지 못함 (TaskRejectedException)");
    }

    private RunRuleScan ruleScan(TaskExecutor executor) {
        return new RunRuleScan(scanRecorder, scanner, batchRuns, currentUser, chain, executor, progress);
    }

    private RequestScores requestScores(TaskExecutor executor) {
        AmlProperties props = mock(AmlProperties.class, RETURNS_DEEP_STUBS);
        when(props.ai().maxBatch()).thenReturn(500);
        return new RequestScores(scoreRecorder, mock(ScoreChunkWriter.class), scores, mock(AiClient.class), batchRuns,
                currentUser, props, new SimpleMeterRegistry(), chain, executor);
    }

    private static BatchRunView view(long id, BatchType type) {
        return new BatchRunView(id, type, DATE, BatchStatus.RUNNING, Instant.parse("2026-11-22T00:00:00Z"), null,
                null, null, null, null, null, new BatchRunView.TriggeredBy("admin"));
    }
}
