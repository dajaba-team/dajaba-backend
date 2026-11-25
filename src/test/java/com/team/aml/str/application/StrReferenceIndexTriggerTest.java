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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
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
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** 서버 종료 중·큐가 꽉 찬 실행기 흉내. */
    private final TaskExecutor rejecting = task -> { throw new TaskRejectedException("queue full"); };

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
        return newTrigger(enabled, inline);
    }

    private StrReferenceIndexTrigger newTrigger(boolean enabled, TaskExecutor executor) {
        return new StrReferenceIndexTrigger(
                aiClient, drafts, reports, cases, getAlertForDraft,
                txTemplate, executor, props(enabled), meters);
    }

    private double rejected() {
        Counter c = meters.find("aml.str.index.rejected").counter();
        return c == null ? 0 : c.count();
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

    // --- 11/25 전체 점검 BH-BE-04: 색인 실행기가 제출을 거절해도 승인은 200 ---

    @Test
    void 실패_커밋_뒤_실행기가_거절해도_승인_트랜잭션_호출자에게_예외가_가지_않고_지표가_1이다() {
        // 진짜 트랜잭션 동기화(afterCommit 콜백) — 승인(@Transactional) 커밋 뒤 콜백에서 던지면 요청이 500 이 됐다
        TransactionTemplate approveTx = new TransactionTemplate(new NoOpTransactionManager());
        StrReferenceIndexTrigger trigger = newTrigger(true, rejecting);

        assertThatCode(() -> approveTx.executeWithoutResult(s -> trigger.afterCommit(10L)))
                .doesNotThrowAnyException();

        assertThat(rejected()).isEqualTo(1.0);
        verify(aiClient, never()).indexApprovedStr(any());
    }

    @Test
    void 실패_트랜잭션_밖에서_바로_제출할_때_실행기가_거절해도_예외가_없고_지표가_1이다() {
        StrReferenceIndexTrigger trigger = newTrigger(true, rejecting);

        assertThatCode(() -> trigger.afterCommit(10L)).doesNotThrowAnyException();

        assertThat(rejected()).isEqualTo(1.0);
    }

    @Test
    void 경계_거절이_두_번이면_지표도_2이다() {
        StrReferenceIndexTrigger trigger = newTrigger(true, rejecting);

        trigger.afterCommit(10L);
        trigger.afterCommit(11L);

        assertThat(rejected()).isEqualTo(2.0);
    }

    @Test
    void 정상_실행기가_받으면_색인을_부르고_거절_지표는_없다() {
        NarrativeSection section = new NarrativeSection(
                StrSection.SUBJECT, "본문 내용", List.of(), List.of());
        stubFixtures(10L, 200L, 300L, null, List.of(section));
        when(aiClient.indexApprovedStr(any()))
                .thenReturn(new StrIndexResponse(
                        "2026-11-24T01:01:00Z", "STR-2026-000001",
                        StrIndexStatus.INDEXED, null, 1, "ret-v1", null));
        TransactionTemplate approveTx = new TransactionTemplate(new NoOpTransactionManager());
        StrReferenceIndexTrigger trigger = newTrigger(true);

        approveTx.executeWithoutResult(s -> trigger.afterCommit(10L));

        verify(aiClient, times(1)).indexApprovedStr(any());
        assertThat(rejected()).isZero();
    }

    /** DB 없이 커밋 동기화(afterCommit)만 진짜로 도는 트랜잭션 관리자. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
