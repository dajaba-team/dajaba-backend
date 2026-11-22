package com.team.aml.str.application;

import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos.StrIndexRequest;
import com.team.aml.shared.ai.AiDtos.StrIndexResponse;
import com.team.aml.shared.ai.AiDtos.StrIndexSkipReason;
import com.team.aml.shared.ai.AiDtos.StrIndexStatus;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrReferenceIndexTriggerTest {

    private final AiClient aiClient = mock(AiClient.class);
    private final StrDraftRepository drafts = mock(StrDraftRepository.class);
    private final StrReportRepository reports = mock(StrReportRepository.class);
    private final TaskExecutor inline = Runnable::run;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private AmlProperties props(boolean enabled) {
        AmlProperties.Ai ai = new AmlProperties.Ai(
                "http://localhost:8000", "test-key-123456789012345678901234",
                90, 500, true, 30, false, 5, 8, false, 30, enabled);
        return new AmlProperties(ai, null, null, null);
    }

    @Test
    void 스위치_꺼짐이면_호출하지_않는다() {
        StrReferenceIndexTrigger trigger = new StrReferenceIndexTrigger(
                aiClient, drafts, reports, inline, props(false));

        trigger.afterCommit(1L);

        verify(aiClient, never()).indexApprovedStr(any());
    }

    @Test
    void 트랜잭션_롤백되면_호출하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        StrReferenceIndexTrigger trigger = new StrReferenceIndexTrigger(
                aiClient, drafts, reports, inline, props(true));

        trigger.afterCommit(1L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(aiClient, never()).indexApprovedStr(any());
    }

    @Test
    void ai_예외가_발생해도_전파하지_않는다() {
        StrDraft draft = mock(StrDraft.class);
        StrReport report = mock(StrReport.class);
        when(drafts.findById(10L)).thenReturn(Optional.of(draft));
        when(reports.findByDraftId(10L)).thenReturn(Optional.of(report));
        when(draft.currentBody()).thenReturn(java.util.List.of());
        when(draft.getSuspicionTypes()).thenReturn(java.util.List.of());
        when(report.getReportNo()).thenReturn("STR-2026-000001");
        when(report.getApprovedAt()).thenReturn(java.time.Instant.parse("2026-11-24T01:00:00Z"));
        when(aiClient.indexApprovedStr(any())).thenThrow(new RuntimeException("timeout"));

        StrReferenceIndexTrigger trigger = new StrReferenceIndexTrigger(
                aiClient, drafts, reports, inline, props(true));

        trigger.afterCommit(10L);

        verify(aiClient).indexApprovedStr(any(StrIndexRequest.class));
    }
}