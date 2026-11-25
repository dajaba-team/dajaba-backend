package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseDlRank;
import com.team.aml.cases.application.ListCases;
import com.team.aml.cases.application.UserRef;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.cases.domain.ScoreBand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 목록 한 줄 (계약서 C1). 계좌는 가린 account_no + 구별용 account_ref 만 — account_key 는 없다.
 * score 는 케이스 생성 당시 점수(V013, SCRUM-236 — 경보 점수는 바뀌지 않아 상세 latest_score.score 와 같음),
 * score_band·policy_version 은 지금 운영 정책으로 계산(정책이 없으면 null).
 * available_actions 는 상세(C2)와 같은 규칙 — 목록 버튼도 화면이 상태로 계산하지 않고 이것만 본다 (#23 리뷰).
 * dl_rank 는 ML+DL 조사 순위(CaseDlRank, SCRUM-420) — 순위를 붙이지 못했으면 UNAVAILABLE 로 채운다(null 아님).
 */
public record CaseListItemResponse(String caseNo, CaseStatus status, String accountRef, String accountNo,
                                   String entityId, UserRef assignee, BigDecimal score, ScoreBand scoreBand,
                                   String policyVersion, RejectionReasonCode rejectionReasonCode, long version,
                                   Instant createdAt, List<CaseAction> availableActions, CaseDlRank dlRank) {

    static CaseListItemResponse from(ListCases.Item item, CaseDlRank dlRank) {
        return new CaseListItemResponse(item.caseNo(), item.status(), AccountDisplay.ref(item.accountKey()),
                AccountDisplay.maskedNo(item.accountKey()), item.entityId(), item.assignee(), item.score(),
                item.scoreBand(), item.policyVersion(),
                item.rejectionReasonCode(), item.version(), item.createdAt(), item.availableActions(),
                CaseDlRank.orUnavailable(dlRank));
    }
}
