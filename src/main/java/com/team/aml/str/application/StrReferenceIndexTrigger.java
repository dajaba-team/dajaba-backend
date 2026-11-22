package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.detection.application.GetAlertForDraft;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiDtos;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrReport;
import com.team.aml.str.domain.StrReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * STR 승인 커밋 뒤에 승인된 STR 섹션을 AI 참고 사례 색인(/v1/references/str)으로 비동기 전송한다.
 * - 승인 트랜잭션 안에서 호출하지 않고 afterCommit 에서 executor 로 넘긴다.
 * - 색인 실패나 SKIPPED 가 발생해도 승인 결과에 영향을 주지 않는다.
 */
@Component
public class StrReferenceIndexTrigger {

    private static final Logger log = LoggerFactory.getLogger(StrReferenceIndexTrigger.class);

    private final AiClient aiClient;
    private final StrDraftRepository drafts;
    private final StrReportRepository reports;
    private final CaseRepository cases;
    private final GetAlertForDraft getAlertForDraft;
    private final TransactionTemplate txTemplate;
    private final Executor executor;
    private final AmlProperties properties;

    public StrReferenceIndexTrigger(AiClient aiClient,
                                    StrDraftRepository drafts,
                                    StrReportRepository reports,
                                    CaseRepository cases,
                                    GetAlertForDraft getAlertForDraft,
                                    TransactionTemplate txTemplate,
                                    Executor executor,
                                    AmlProperties properties) {
        this.aiClient = aiClient;
        this.drafts = drafts;
        this.reports = reports;
        this.cases = cases;
        this.getAlertForDraft = getAlertForDraft;
        this.txTemplate = txTemplate;
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * 현재 트랜잭션이 커밋된 뒤에만 색인을 실행하도록 등록한다.
     * 트랜잭션이 없거나 동기화가 비활성화된 환경에서는 즉시 실행한다.
     */
    public void afterCommit(long draftId) {
        if (!properties.ai().strIndexEnabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(draftId);
                }
            });
        } else {
            dispatch(draftId);
        }
    }

    private void dispatch(long draftId) {
        executor.execute(() -> indexSafely(draftId));
    }

    @Async
    void indexSafely(long draftId) {
        if (!properties.ai().strIndexEnabled()) {
            return;
        }
        try {
            AiDtos.StrIndexRequest request = buildRequestInTx(draftId);
            if (request == null || request.sections().isEmpty()) {
                log.warn("색인 대상 섹션이 없어 호출을 건너뜀 draft_id={}", DisplayNumber.draftNo(draftId));
                return;
            }

            AiDtos.StrIndexResponse response = aiClient.indexApprovedStr(request);
            if (response != null) {
                log.info("승인 STR 색인 완료 draft_id={} report_no={} status={} skip_reason={} chunk_count={}",
                        request.draftId(), request.reportNo(), response.status(),
                        response.skipReason(), response.chunkCount());
            }
        } catch (Exception ex) {
            // 색인 실패는 승인 결과를 롤백하지 않고 로그만 남긴다. (MVP 재시도 없음)
            log.warn("승인 STR 색인 호출 실패 draft_id={}", DisplayNumber.draftNo(draftId), ex);
        }
    }

    private AiDtos.StrIndexRequest buildRequestInTx(long draftId) {
        return txTemplate.execute(status -> {
            StrDraft draft = drafts.findById(draftId).orElse(null);
            StrReport report = reports.findByDraftId(draftId).orElse(null);
            if (draft == null || report == null) {
                log.warn("색인용 초안 또는 보고서를 찾지 못함 draft_id={}", DisplayNumber.draftNo(draftId));
                return null;
            }

            List<String> typologies = resolveTypologies(draft.getCaseId());
            List<AiDtos.StrIndexSection> sections = toSections(draft.currentBody());

            String draftNo = DisplayNumber.draftNo(draftId);
            String reportNo = report.getReportNo();
            Instant approvedAt = report.getApprovedAt();
            String approvedAtIsoUtc = approvedAt != null ? DateTimeFormatter.ISO_INSTANT.format(approvedAt) : null;

            return new AiDtos.StrIndexRequest(
                    reportNo,
                    draftNo,
                    approvedAtIsoUtc,
                    typologies,
                    sections
            );
        });
    }

    /**
     * draft.getCaseId() -> 케이스 -> GetAlertForDraft.execute(alertId).alert().typologyHint()
     */
    private List<String> resolveTypologies(Long caseId) {
        if (caseId == null || cases == null || getAlertForDraft == null) {
            return List.of();
        }
        Case targetCase = cases.findById(caseId).orElse(null);
        if (targetCase == null || targetCase.getAlertId() == null) {
            return List.of();
        }

        AlertForDraft alertForDraft = getAlertForDraft.execute(targetCase.getAlertId());
        if (alertForDraft == null || alertForDraft.alert() == null) {
            return List.of();
        }

        String hint = alertForDraft.alert().typologyHint();
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        return List.of(hint.trim());
    }

    /**
     * 단락을 자르거나 제외하지 않고 그대로 전송한다.
     */
    private List<AiDtos.StrIndexSection> toSections(List<NarrativeSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return List.of();
        }
        return sections.stream()
                .map(section -> new AiDtos.StrIndexSection(
                        section.section().name(),
                        section.text()
                ))
                .toList();
    }
}