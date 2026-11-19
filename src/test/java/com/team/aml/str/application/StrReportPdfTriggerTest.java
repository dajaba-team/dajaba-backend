package com.team.aml.str.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 커밋 뒤 확정본 PDF 걸기 (스프링·DB 없이). 실행기는 그 자리에서 돌리는 것으로 바꿔 순서만 본다.
 * 기준: 서식 회의 10/30 결정 2 — 생성은 커밋 뒤, 실패해도 제출은 그대로.
 */
class StrReportPdfTriggerTest {

    private final GenerateStrReportPdf generate = mock(GenerateStrReportPdf.class);
    private final TaskExecutor inline = Runnable::run;

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // 1
    @Test
    void 정상_트랜잭션_안에서는_커밋된_뒤에만_만든다() {
        TransactionSynchronizationManager.initSynchronization();
        new StrReportPdfTrigger(generate, inline, true).afterCommit(7L);

        verify(generate, never()).execute(anyLong());                          // 아직 커밋 전
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(generate).execute(7L);
    }

    // 2
    @Test
    void 정상_롤백되면_만들지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        new StrReportPdfTrigger(generate, inline, true).afterCommit(7L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(generate, never()).execute(anyLong());
    }

    // 3
    @Test
    void 실패_실행기가_거절하면_그_자리에서_FAILED_NOT_STARTED() {   // PENDING 이 남으면 P5 로도 못 살린다
        TaskExecutor full = task -> { throw new RejectedExecutionException("queue full"); };

        new StrReportPdfTrigger(generate, full, true).afterCommit(7L);       // 트랜잭션 밖 — 바로 건다

        verify(generate).fail(7L, GenerateStrReportPdf.NOT_STARTED);
        verify(generate, never()).execute(anyLong());
    }

    // 4
    @Test
    void 정상_만들다_예외가_나도_밖으로_던지지_않는다() {
        doThrow(new IllegalStateException("db down")).when(generate).execute(7L);

        new StrReportPdfTrigger(generate, inline, true).afterCommit(7L);      // 던지지 않으면 통과

        verify(generate).execute(7L);
    }

    // 5
    @Test
    void 정상_꺼져_있으면_아무것도_안_건다() {
        new StrReportPdfTrigger(generate, inline, false).afterCommit(7L);

        verify(generate, never()).execute(anyLong());
        verify(generate, never()).fail(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
