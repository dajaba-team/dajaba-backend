package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.detection.application.GetAlertForDraft;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import com.team.aml.shared.ai.AiDtos.StrIndexRequest;
import com.team.aml.shared.ai.AiDtos.StrIndexResponse;
import com.team.aml.shared.ai.AiDtos.StrIndexSkipReason;
import com.team.aml.shared.ai.AiDtos.StrIndexStatus;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrReferenceIndexTriggerTest {

    private final AiClient aiClient = mock(AiClient.class);
    private final StrDraftRepository drafts = mock(StrDraftRepository.class);
    private final StrReportRepository reports = mock(StrReportRepository.class);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final GetAlertForDraft getAlertForDraft = mock(GetAlertForDraft.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate txTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final TaskExecutor inline = Runnable::run;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

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

    private StrReferenceIndexTrigger newTrigger(boolean enabled) {
        return new StrReferenceIndexTrigger(
                aiClient, drafts, reports, cases, getAlertForDraft,
                txTemplate, inline, props(enabled));
    }

    private void stubFixtures(long draftId, long caseId, long alertId, String typologyHint,
                              List<NarrativeSection> sections) {
        StrDraft draft = mock(StrDraft.class);
        StrReport report = mock(StrReport.class);
        Case targetCase = mock(Case.class);
        AlertForDraft alertForDraft = mock(AlertForDraft.class);
        AlertPayload alertPayload = mock(AlertPayload.class);

        when(drafts.findById(draftId)).thenReturn(Optional.of(draft));
        when(reports.findByDraftId(draftId)).thenReturn(Optional.of(report));
        when(draft.getCaseId()).thenReturn(caseId);
        when(draft.currentBody()).thenReturn(sections);
        when(report.getReportNo()).thenReturn("STR-2026-000001");
        when(report.getApprovedAt()).thenReturn(Instant.parse("2026-11-24T01:00:00Z"));

        when(cases.findById(caseId)).thenReturn(Optional.of(targetCase));
        when(targetCase.getAlertId()).thenReturn(alertId);
        when(getAlertForDraft.execute(alertId)).thenReturn(alertForDraft);
        when(alertForDraft.alert()).thenReturn(alertPayload);
        when(alertPayload.typologyHint()).thenReturn(typologyHint);
    }

    @Test
    void 스위치_꺼짐이면_호출하지_않는다() {
        StrReferenceIndexTrigger trigger = newTrigger(false);

        trigger.afterCommit(1L);

        verify(aiClient, never()).indexApprovedStr(any());
    }

    @Test
    void 트랜잭션_롤백되면_호출하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        StrReferenceIndexTrigger trigger = newTrigger(true);

        trigger.afterCommit(1L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(aiClient, never()).indexApprovedStr(any());
    }

    @Test
    void 커밋_뒤_afterCommit_호출_시_indexApprovedStr_1회_호출_및_요청_검증() {
        TransactionSynchronizationManager.initSynchronization();

        String longText = "가".repeat(1_500);
        NarrativeSection section = new NarrativeSection(
                StrSection.SUBJECT, longText, List.of("period"), List.of(101L));
        stubFixtures(10L, 200L, 300L, "SMURFING", List.of(section));
        when(aiClient.indexApprovedStr(any()))
                .thenReturn(new StrIndexResponse(
                        "2026-11-24T01:01:00Z", "STR-2026-000001",
                        StrIndexStatus.INDEXED, null, 2, "ret-v1", null));

        StrReferenceIndexTrigger trigger = newTrigger(true);
        trigger.afterCommit(10L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<StrIndexRequest> captor = ArgumentCaptor.forClass(StrIndexRequest.class);
        verify(aiClient, times(1)).indexApprovedStr(captor.capture());

        StrIndexRequest captured = captor.getValue();
        assertThat(captured.reportNo()).isEqualTo("STR-2026-000001");
        assertThat(captured.draftId()).isEqualTo("DRF-000010");
        assertThat(captured.typologies()).containsExactly("SMURFING");
        assertThat(captured.sections()).hasSize(1);
        assertThat(captured.sections().get(0).section()).isEqualTo("SUBJECT");
        assertThat(captured.sections().get(0).text()).hasSize(1_500);
    }

    @Test
    void INDEXED_및_SKIPPED_응답_시_예외_없이_끝난다() {
        NarrativeSection section = new NarrativeSection(
                StrSection.SUBJECT, "본문 내용", List.of(), List.of());
        stubFixtures(10L, 200L, 300L, null, List.of(section));

        StrReferenceIndexTrigger trigger = newTrigger(true);

        when(aiClient.indexApprovedStr(any()))
                .thenReturn(new StrIndexResponse(
                        "2026-11-24T01:01:00Z", "STR-2026-000001",
                        StrIndexStatus.INDEXED, null, 1, "ret-v1", null));
        assertThatCode(() -> trigger.afterCommit(10L)).doesNotThrowAnyException();

        when(aiClient.indexApprovedStr(any()))
                .thenReturn(new StrIndexResponse(
                        "2026-11-24T01:01:00Z", "STR-2026-000001",
                        StrIndexStatus.SKIPPED, StrIndexSkipReason.TOO_SHORT, 0, "ret-v1", null));
        assertThatCode(() -> trigger.afterCommit(10L)).doesNotThrowAnyException();
    }

    @Test
    void ai_예외가_발생해도_전파하지_않는다() {
        NarrativeSection section = new NarrativeSection(
                StrSection.SUBJECT, "본문 내용", List.of(), List.of());
        stubFixtures(10L, 200L, 300L, "LAYERING", List.of(section));
        when(aiClient.indexApprovedStr(any())).thenThrow(new RuntimeException("timeout"));

        StrReferenceIndexTrigger trigger = newTrigger(true);

        assertThatCode(() -> trigger.afterCommit(10L)).doesNotThrowAnyException();
        verify(aiClient, times(1)).indexApprovedStr(any(StrIndexRequest.class));
    }
}