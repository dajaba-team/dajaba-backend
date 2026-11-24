package com.team.aml.str.application;

import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrDraftMessage;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 초안 생성 작업 넘기기 (스프링·DB 없이) — 큐가 받으면 큐로, 아니면 앱 안에서. 확정본 PDF 트리거와 같은 모양. */
class StrDraftTriggerTest {

    private final GenerateStrDraft generate = mock(GenerateStrDraft.class);
    private final JobPublisher publisher = mock(JobPublisher.class);
    private final TaskExecutor inline = Runnable::run;

    // 1
    @Test
    void 정상_브로커가_받으면_큐로만_보내고_앱_안에서는_안_만든다() {
        when(publisher.publish(eq(JobQueues.STR_DRAFT_GENERATE), any(StrDraftMessage.class))).thenReturn(true);

        new StrDraftTrigger(generate, publisher, inline, true).submit(45L);

        verify(publisher).publish(JobQueues.STR_DRAFT_GENERATE, new StrDraftMessage(45L));
        verify(generate, never()).execute(anyLong());
    }

    // 2
    @Test
    void 정상_큐가_꺼졌거나_보내기_실패면_앱_안에서_만든다() {
        when(publisher.publish(any(), any())).thenReturn(false);

        new StrDraftTrigger(generate, publisher, inline, true).submit(45L);

        verify(generate).execute(45L);
    }

    // 3
    @Test
    void 정상_앱_안에서_만들다_예외가_나도_밖으로_던지지_않는다() {   // 실패 기록은 GenerateStrDraft 가 한다
        when(publisher.publish(any(), any())).thenReturn(false);
        doThrow(new IllegalStateException("AI down")).when(generate).execute(45L);

        new StrDraftTrigger(generate, publisher, inline, true).submit(45L);

        verify(generate).execute(45L);
    }

    // 4
    @Test
    void 실패_실행기가_거절하면_그_자리에서_FAILED_NOT_STARTED() {   // REQUESTED 로 남으면 다음 요청이 409
        TaskExecutor full = task -> { throw new RejectedExecutionException("queue full"); };

        new StrDraftTrigger(generate, publisher, full, true).submit(45L);

        verify(generate).fail(45L, StrDraftTrigger.NOT_STARTED);
        verify(publisher, never()).publish(any(), any());
    }

    // 5
    @Test
    void 정상_꺼져_있으면_아무것도_안_한다() {
        new StrDraftTrigger(generate, publisher, inline, false).submit(45L);

        verify(publisher, never()).publish(any(), any());
        verify(generate, never()).execute(anyLong());
        verify(generate, never()).fail(anyLong(), anyString());
    }
}
