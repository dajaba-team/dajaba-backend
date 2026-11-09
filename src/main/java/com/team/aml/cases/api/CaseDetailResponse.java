package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseSummaryResult;
import com.team.aml.cases.application.GetCaseDetail;
import com.team.aml.cases.application.UserRef;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.cases.domain.ScoreBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 통합 상세 (계약서 C2, CASES 명세 API-02). 키는 계약서 모양 그대로 두고, 아직 채울 수 없는 칸은 null·[] 로 보낸다.
 * <ul>
 *   <li>alert·latest_score — detection GetAlertSummary 로 채운다 (SCRUM-152)</li>
 *   <li>score·score_band·policy_version — 계약서 Q2 MVP: latest_score 와 같은 값 (생성 때 스냅샷은 후속)</li>
 *   <li>latest_summary — API-13 과 같은 값 (요약 없으면 null, SCRUM-175)</li>
 *   <li>aggregates·investigator_notes — API-08·05 후</li>
 *   <li>current_draft 본문 — str 조합 계층 후 (노션 미결 17). 번호 current_draft_no 는 지금 준다</li>
 *   <li>customer·prior_alerts — 후속</li>
 * </ul>
 * 화면 응답이라 Object 칸(alert 등)은 모양이 정해지면 record 로 바꾼다.
 */
public record CaseDetailResponse(String caseNo, CaseStatus status, String accountRef, String accountNo,
                                 String entityId, UserRef assignee, BigDecimal score, ScoreBand scoreBand,
                                 String policyVersion, RejectionReasonCode rejectionReasonCode, long version,
                                 Instant createdAt, Instant updatedAt, GetCaseDetail.Alert alert, Object customer,
                                 String investigatorNotes, CloseReason closeReason, GetCaseDetail.LatestScore latestScore,
                                 Object aggregates, CaseSummaryResult.Summary latestSummary, String currentDraftNo, Object currentDraft,
                                 List<Object> priorAlerts, List<GetCaseDetail.History> history,
                                 List<CaseAction> availableActions) {

    public CaseDetailResponse {
        priorAlerts = priorAlerts == null ? List.of() : List.copyOf(priorAlerts);
        history = history == null ? List.of() : List.copyOf(history);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    }

    static CaseDetailResponse from(GetCaseDetail.Result r) {
        return new CaseDetailResponse(r.caseNo(), r.status(), AccountDisplay.ref(r.accountKey()),
                AccountDisplay.maskedNo(r.accountKey()), r.entityId(), r.assignee(), r.latestScore().score(),
                r.latestScore().scoreBand(), r.policyVersion(),
                r.rejectionReasonCode(), r.version(), r.createdAt(), r.updatedAt(), r.alert(), null, null,
                r.closeReason(), r.latestScore(), null, r.latestSummary(),
                CaseChangeResponse.draftNo(r.currentDraftId()), null, List.of(), r.history(), r.availableActions());
    }
}
