package com.team.aml.cases.application;

import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 — 큐가 받으면 큐로, 못 받으면 기존 실행기로 (fail-open, SCRUM-276). */
class RequestOpenCasesTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 3);

    private final OpenCasesFromAlerts openCases = mock(OpenCasesFromAlerts.class);
    private final OpenCasesRecorder recorder = mock(OpenCasesRecorder.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final JobPublisher publisher = mock(JobPublisher.class);
    private final RequestOpenCases request = new RequestOpenCases(openCases, recorder, currentUser, executor, publisher);

    RequestOpenCasesTest() {
        when(currentUser.require()).thenReturn(new AppUserDetails(1L, "admin", "관리자", "ADMIN"));
        when(openCases.start(DATE, 1L)).thenReturn(42L);
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
}
