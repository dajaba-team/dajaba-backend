package com.team.aml.str.application;

import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.messaging.StrReportPdfMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 커밋 뒤 RabbitMQ 발행, 실패 시 기존 로컬 실행기 fallback을 확인한다. */
class StrReportPdfTriggerTest {

    private final GenerateStrReportPdf generate = mock(GenerateStrReportPdf.class);
    private final JobPublisher publisher = mock(JobPublisher.class);
    private final TaskExecutor inline = Runnable::run;

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 정상_트랜잭션_커밋_뒤에만_RabbitMQ로_발행한다() {
        when(publisher.publish(eq(JobQueues.STR_PDF_GENERATE), any(StrReportPdfMessage.class))).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        new StrReportPdfTrigger(generate, publisher, inline, true).afterCommit(7L);

        verify(publisher, never()).publish(eq(JobQueues.STR_PDF_GENERATE), any(StrReportPdfMessage.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(publisher).publish(JobQueues.STR_PDF_GENERATE, new StrReportPdfMessage(7L));
        verify(generate, never()).execute(anyLong());
    }

    @Test
    void 정상_롤백되면_발행하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        new StrReportPdfTrigger(generate, publisher, inline, true).afterCommit(7L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(publisher, never()).publish(any(), any());
        verify(generate, never()).execute(anyLong());
    }

    @Test
    void 정상_RabbitMQ가_꺼져있거나_발행에_실패하면_로컬에서_생성한다() {
        when(publisher.publish(eq(JobQueues.STR_PDF_GENERATE), any(StrReportPdfMessage.class))).thenReturn(false);

        new StrReportPdfTrigger(generate, publisher, inline, true).afterCommit(7L);

        verify(publisher).publish(JobQueues.STR_PDF_GENERATE, new StrReportPdfMessage(7L));
        verify(generate).execute(7L);
    }

    @Test
    void 실패_실행기가_거절하면_FAILED_NOT_STARTED를_기록한다() {
        TaskExecutor full = task -> { throw new RejectedExecutionException("queue full"); };

        new StrReportPdfTrigger(generate, publisher, full, true).afterCommit(7L);

        verify(generate).fail(7L, GenerateStrReportPdf.NOT_STARTED);
        verify(publisher, never()).publish(any(), any());
        verify(generate, never()).execute(anyLong());
    }

    @Test
    void 정상_로컬_생성_예외는_호출자에게_전파하지_않는다() {
        when(publisher.publish(eq(JobQueues.STR_PDF_GENERATE), any(StrReportPdfMessage.class))).thenReturn(false);
        doThrow(new IllegalStateException("db down")).when(generate).execute(7L);

        new StrReportPdfTrigger(generate, publisher, inline, true).afterCommit(7L);

        verify(generate).execute(7L);
    }

    @Test
    void 정상_꺼져_있으면_아무것도_하지_않는다() {
        new StrReportPdfTrigger(generate, publisher, inline, false).afterCommit(7L);

        verify(publisher, never()).publish(any(), any());
        verify(generate, never()).execute(anyLong());
        verify(generate, never()).fail(anyLong(), any());
    }
}
