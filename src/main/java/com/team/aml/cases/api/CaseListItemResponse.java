package com.team.aml.cases.api;

import com.team.aml.cases.application.ListCases;
import com.team.aml.cases.application.UserRef;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.cases.domain.ScoreBand;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 목록 한 줄 (계약서 C1). 계좌는 가린 account_no + 구별용 account_ref 만 — account_key 는 없다.
 * score·score_band·policy_version 은 detection 점수 공개 조회가 생길 때까지 null (SCRUM-152).
 */
public record CaseListItemResponse(String caseNo, CaseStatus status, String accountRef, String accountNo,
                                   String entityId, UserRef assignee, BigDecimal score, ScoreBand scoreBand,
                                   String policyVersion, RejectionReasonCode rejectionReasonCode, long version,
                                   Instant createdAt) {

    static CaseListItemResponse from(ListCases.Item item) {
        return new CaseListItemResponse(item.caseNo(), item.status(), AccountDisplay.ref(item.accountKey()),
                AccountDisplay.maskedNo(item.accountKey()), item.entityId(), item.assignee(), null, null, null,
                item.rejectionReasonCode(), item.version(), item.createdAt());
    }
}
