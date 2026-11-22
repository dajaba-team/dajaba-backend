package com.team.aml.str.application;

import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos.StrIndexRequest;
import com.team.aml.shared.ai.AiDtos.StrIndexResponse;
import com.team.aml.shared.ai.AiDtos.StrIndexSection;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * STR 승인 커밋 뒤에 승인된 STR 섹션을 AI 참고 사례 색인(/v1/references/str)으로 비동기 전송한다.
 *
 * - 승인 트랜잭션 안에서 호출하지 않고 afterCommit 에서 applicationTaskExecutor 로 넘긴다.
 * - 색인 실패나 SKIPPED 가 발생해도 승인 결과에 영향을 주지 않는다.
 */
@Component
public class StrReferenceIndexTrigger {

    private static final Logger log = LoggerFactory.getLogger(StrReferenceIndexTrigger.class);

    private final AiClient aiClient;
    private final StrDraftRepository drafts;
    private final StrReportRepository reports;
    private final TransactionTemplate txTemplate;
    private final TaskExecutor executor;
    private final boolean enabled;

    public StrReferenceIndexTrigger(AiClient aiClient,
                                    StrDraftRepository drafts,
                                    StrReportRepository reports,
                                    TransactionTemplate txTemplate,
                                    @Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                    AmlProperties properties) {
        this.aiClient = aiClient;
        this.drafts = drafts;
        this.reports = reports;
        this.txTemplate = txTemplate;
        this.executor = executor;
        this.enabled = properties != null && properties.ai() != null && properties.ai().strIndexEnabled();
    }

    /**
     * 현재 트랜잭션이 커밋된 뒤 draftId 에 대한 승인 STR 색인을 한 번 실행한다.
     * 트랜잭션 동기화가 없는 상태면 즉시 제출한다.
     */
    public void afterCommit(long draftId) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(draftId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(draftId);
            }
        });
    }

    private void submit(long draftId) {
        try {
            executor.execute(() -> runSafely(draftId));
        } catch (RuntimeException e) {
            log.warn("승인 STR 색인 작업 제출 실패 draft_no={} cause={}",
                    DisplayNumber.draftNo(draftId), e.getClass().getSimpleName());
        }
    }

    private void runSafely(long draftId) {
        String draftNo = DisplayNumber.draftNo(draftId);
        try {
            StrIndexRequest request = txTemplate.execute(status -> buildRequestInTx(draftId, draftNo));
            if (request == null) {
                return;
            }

            StrIndexResponse response = aiClient.indexApprovedStr(request);
            log.info("승인 STR 색인 완료 report_no={} draft_no={} status={} skip_reason={} chunk_count={}",
                    response.reportNo(),
                    draftNo,
                    response.status(),
                    response.skipReason(),
                    response.chunkCount());
        } catch (RuntimeException e) {
            log.warn("승인 STR 색인 실패 draft_no={} cause={}",
                    draftNo, e.getClass().getSimpleName());
        }
    }

    private StrIndexRequest buildRequestInTx(long draftId, String draftNo) {
        StrDraft draft = drafts.findById(draftId).orElse(null);
        StrReport report = reports.findByDraftId(draftId).orElse(null);
        if (draft == null || report == null) {
            log.warn("승인 STR 색인 대상 누락 draft_no={} draft_found={} report_found={}",
                    draftNo, draft != null, report != null);
            return null;
        }

        List<StrIndexSection> sections = draft.currentBody().stream()
                .map(s -> new StrIndexSection(s.section().name(), s.text()))
                .toList();

        return new StrIndexRequest(
                report.getReportNo(),
                draftNo,
                report.getApprovedAt().toString(),
                List.copyOf(draft.getSuspicionTypes()),
                sections
        );
    }
}