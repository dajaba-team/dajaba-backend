package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseAggregates;
import com.team.aml.cases.application.CaseDlRank;
import com.team.aml.cases.application.CaseSummaryResult;
import com.team.aml.cases.application.GetCaseDetail;
import com.team.aml.cases.application.UserRef;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.CloseReason;
import com.team.aml.cases.domain.OpenedScore;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.cases.domain.ScoreBand;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

/**
 * 통합 상세 (계약서 C2, CASES 명세 API-02). 키는 계약서 모양 그대로 두고, 아직 채울 수 없는 칸은 null·[] 로 보낸다.
 * <ul>
 *   <li>alert·latest_score — detection GetAlertSummary 로 채운다 (SCRUM-152)</li>
 *   <li>score·score_band·policy_version — 계약서 Q2 MVP: latest_score 와 같은 값</li>
 *   <li>opened_score — 케이스 생성 당시 점수·모델·정책 스냅샷 (V013, SCRUM-237). 없으면 null. 계약서 C2 에 추가 필요</li>
 *   <li>latest_summary — API-13 과 같은 값 (요약 없으면 null, SCRUM-175)</li>
 *   <li>investigator_notes — 마지막 NOTE 행 (메모를 쓴 적이 없으면 null, SCRUM-176)</li>
 *   <li>aggregates — 근거 거래 전체로 통화별 유입·유출, 경유·자기계좌 거래 제외 (SCRUM-179)</li>
 *   <li>current_draft — null 로 둔다(11/22 확정, SCRUM-245). 번호 current_draft_no 만 주고 초안은 화면이 S2 를 따로 부른다</li>
 *   <li>customer — null 로 둔다(11/22 MVP 확정, SCRUM-245). 출처 ops.customer_attributes 는 있으나 화면·계약 모양 전</li>
 *   <li>prior_alerts — 같은 법인의 이전 경보 최근 20건, 계좌는 가린 값만 (detection FindAlertsByEntity, SCRUM-239)</li>
 *   <li>dl_rank — ML+DL 조사 순위 (CaseDlRank, SCRUM-420). 목록과 같은 모양, 붙이지 못했으면 UNAVAILABLE(null 아님)</li>
 * </ul>
 * 화면 응답이라 Object 칸(alert 등)은 모양이 정해지면 record 로 바꾼다.
 */
public record CaseDetailResponse(String caseNo, CaseStatus status, String accountRef, String accountNo,
                                 String entityId, UserRef assignee, BigDecimal score, ScoreBand scoreBand,
                                 String policyVersion, RejectionReasonCode rejectionReasonCode, long version,
                                 Instant createdAt, Instant updatedAt, GetCaseDetail.Alert alert, Object customer,
                                 String investigatorNotes, CloseReason closeReason, GetCaseDetail.LatestScore latestScore,
                                 OpenedScoreResponse openedScore,
                                 CaseAggregates aggregates, CaseSummaryResult.Summary latestSummary, String currentDraftNo, Object currentDraft,
                                 List<PriorAlertResponse> priorAlerts, List<GetCaseDetail.History> history,
                                 List<CaseAction> availableActions, CaseDlRank dlRank) {

    public CaseDetailResponse {
        priorAlerts = priorAlerts == null ? List.of() : List.copyOf(priorAlerts);
        history = history == null ? List.of() : List.copyOf(history);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    }

    /**
     * 생성 당시 스냅샷 (C2 opened_score). 내부 점수 행 id(opened_score_id)는 내보내지 않는다.
     * V013 전에 만든 케이스는 점수·모델만 있고 정책 세 칸은 null.
     */
    public record OpenedScoreResponse(BigDecimal score, String modelVersion, String featureVersion,
                                      String policyVersion, BigDecimal lowToMedium, BigDecimal mediumToHigh) {

        static OpenedScoreResponse from(OpenedScore s) {
            return s == null ? null : new OpenedScoreResponse(s.score(), s.modelVersion(), s.featureVersion(),
                    s.policyVersion(), s.lowToMedium(), s.mediumToHigh());
        }
    }

    /**
     * 이전 경보 한 건 (C2 prior_alerts, SCRUM-239). 원본 account_key·내부 경보 id·정답 칸은 없다.
     * score 는 점수 전·실패면 null, case_no 는 그 경보로 만든 케이스가 없으면 null.
     */
    public record PriorAlertResponse(String alertNo, String ruleId, LocalDate batchDate, LocalDateTime referenceTs,
                                     String accountRef, String accountNo, String scoreStatus, BigDecimal score,
                                     String caseNo) {

        static PriorAlertResponse from(GetCaseDetail.PriorAlertItem p) {
            return new PriorAlertResponse(p.alertNo(), p.ruleId(), p.batchDate(), p.referenceTs(),
                    AccountDisplay.ref(p.accountKey()), AccountDisplay.maskedNo(p.accountKey()), p.scoreStatus(),
                    p.score(), p.caseNo());
        }
    }

    static CaseDetailResponse from(GetCaseDetail.Result r, CaseDlRank dlRank) {
        return new CaseDetailResponse(r.caseNo(), r.status(), AccountDisplay.ref(r.accountKey()),
                AccountDisplay.maskedNo(r.accountKey()), r.entityId(), r.assignee(), r.latestScore().score(),
                r.latestScore().scoreBand(), r.policyVersion(),
                r.rejectionReasonCode(), r.version(), r.createdAt(), r.updatedAt(), r.alert(), null, r.investigatorNotes(),
                r.closeReason(), r.latestScore(), OpenedScoreResponse.from(r.openedScore()), r.aggregates(),
                r.latestSummary(),
                CaseChangeResponse.draftNo(r.currentDraftId()), null,
                r.priorAlerts().stream().map(PriorAlertResponse::from).toList(), r.history(), r.availableActions(),
                CaseDlRank.orUnavailable(dlRank));
    }
}
