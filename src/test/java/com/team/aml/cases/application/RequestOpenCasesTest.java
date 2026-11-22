package com.team.aml.cases.application;

import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 — 큐가 받으면 큐로, 못 받으면 기존 실행기로 (fail-open, SCRUM-276). */
class RequestOpenCasesTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 3);

    private final OpenCasesFromAlerts openCases = mock(OpenCasesFromAlerts.class);
    private final RunOpenCasesOnce runOnce = mock(RunOpenCasesOnce.class);
    private final OpenCasesRecorder recorder = mock(OpenCasesRecorder.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final JobPublisher publisher = mock(JobPublisher.class);
    private final RequestOpenCases request = new RequestOpenCases(openCases, runOnce, recorder, currentUser, executor,
            publisher);

    RequestOpenCasesTest() {
        when(currentUser.require()).thenReturn(new AppUserDetails(1L, "admin", "관리자", "ADMIN"));
        when(openCases.start(DATE, 1L)).thenReturn(42L);
    }

    @Test
    void 배치_잇기_그_뒤_케이스_생성이_이미_시작됐으면_행도_큐도_없이_빈_값() {   // SCRUM-349
        Instant since = Instant.parse("2026-11-22T00:00:00Z");
        when(openCases.startUnlessStartedSince(DATE, 4L, since)).thenReturn(OptionalLong.empty());

        assertThat(request.executeUnlessStartedSince(DATE, 4L, since)).isEmpty();
        verify(publisher, never()).publish(any(), any());
        verify(executor, never()).execute(any());
    }

    @Test
    void 배치_잇기_시작했으면_화면_요청과_같이_큐로_보낸다() {   // SCRUM-349 — 요청자는 넘겨받은 사람(세션 없음)
        Instant since = Instant.parse("2026-11-22T00:00:00Z");
        when(openCases.startUnlessStartedSince(DATE, 4L, since)).thenReturn(OptionalLong.of(43L));
        when(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(43L))).thenReturn(true);

        assertThat(request.executeUnlessStartedSince(DATE, 4L, since)).get()
                .extracting(RequestOpenCases.Accepted::batchRunId).isEqualTo(43L);
        verify(executor, never()).execute(any());
    }

    @Test
    void 정상_큐가_받으면_batch_run_id만_보내고_실행기는_안_쓴다() {
        when(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(42L))).thenReturn(true);

        assertThat(request.execute(DATE).batchRunId()).isEqualTo(42L);
        verify(executor, never()).execute(any());
    }

    @Test
    void 경계_큐가_못_받으면_기존_실행기로_돌린다() {
        when(publisher.publish(any(), any())).thenReturn(false);

        assertThat(request.execute(DATE).batchRunId()).isEqualTo(42L);
        verify(executor).execute(any());
    }

    @Test
    void 경계_실행기_경로도_리스너와_같은_잠금_유스케이스를_거친다() {
        when(publisher.publish(any(), any())).thenReturn(false);
        doAnswer(inv -> { inv.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());

        request.execute(DATE);

        verify(runOnce).execute(42L);
        verify(openCases, never()).run(anyLong(), any());
    }

    @Test
    void 실패_실행기_경로에서_잠금_조회가_실패하면_포기_처리로_RUNNING을_풀어_둔다() {
        when(publisher.publish(any(), any())).thenReturn(false);
        doAnswer(inv -> { inv.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(runOnce.execute(42L)).thenThrow(new IllegalStateException("db down"));

        request.execute(DATE);

        verify(runOnce).giveUp(42L);
    }
}
