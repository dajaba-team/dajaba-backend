package com.team.aml.cases.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseSummaryInput;
import com.team.aml.detection.application.GetAlertForDraft;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.shared.ai.AiDtos.CaseSummaryRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 요약 요청과 그 입력 해시를 만든다 (규약 v0.4 §6.1, CASES 명세 §7.2.1). 요약 갱신(API-12)과
 * is_current 판정(API-13·상세 latest_summary)이 <b>같은 코드</b>로 입력을 만들어야 해시 비교가 맞는다.
 * 트랜잭션은 부르는 쪽 것을 쓴다.
 */
@Component
public class CaseSummaryInputs {

    private final GetAlertForDraft alertForDraft;
    private final ObjectMapper om;

    public CaseSummaryInputs(GetAlertForDraft alertForDraft, ObjectMapper om) {
        this.alertForDraft = alertForDraft;
        this.om = om;
    }

    /**
     * 요청 모양: case_no·alert·evidence_transaction_ids·reasons·prior_alert_count·reporting_institution.
     * alert·reasons·prior_alert_count 는 STR 초안 요청과 같은 값(detection GetAlertForDraft). evidence 는 경보의 거래 전체
     * (중복 제거, 오름차순). 점수가 없으면 reasons 는 [] (AI 는 null 을 422 로 거절). reporting_institution 은 null.
     * 조사 메모는 넣지 않는다 — 메모만 바뀌면 is_current 는 그대로다(CASES 명세 C6).
     */
    CaseSummaryRequest requestFor(Case c) {
        AlertForDraft a = alertForDraft.execute(c.getAlertId());
        List<Long> evidence = a.alert().transactionIds().stream().distinct().sorted().toList();
        JsonNode reasons = a.reasons() != null ? a.reasons() : om.createArrayNode();
        return new CaseSummaryRequest(c.getCaseNo(), a.alert(), evidence, reasons, a.priorAlertCount(), null);
    }

    /** 실제로 보내는 본문 그대로(SNAKE_CASE) — WebClientAiClient 와 같은 ObjectMapper 로 직렬화한다. */
    CaseSummaryInput inputOf(CaseSummaryRequest request) {
        return CaseSummaryInput.of(om.valueToTree(request));
    }

    /** 지금 케이스로 요약을 만든다면 보낼 입력 — is_current 판정용. */
    CaseSummaryInput currentInput(Case c) {
        return inputOf(requestFor(c));
    }
}
