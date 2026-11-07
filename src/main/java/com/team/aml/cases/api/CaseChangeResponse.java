package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseChangeResult;
import com.team.aml.cases.application.UserRef;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.DisplayNumber;

import java.time.Instant;

/** 공통 변경 응답 M (계약서 5.3) — 배정·에스컬레이션·종료가 같은 모양. 초안은 내부 id 가 아니라 draft_no. */
public record CaseChangeResponse(String caseNo, CaseStatus status, UserRef assignee,
                                 RejectionReasonCode rejectionReasonCode, String currentDraftNo,
                                 long version, Instant updatedAt) {

    static CaseChangeResponse from(CaseChangeResult r) {
        return new CaseChangeResponse(r.caseNo(), r.status(), r.assignee(), r.rejectionReasonCode(),
                draftNo(r.currentDraftId()), r.version(), r.updatedAt());
    }

    static String draftNo(Long draftId) {
        return draftId == null ? null : DisplayNumber.draftNo(draftId);
    }
}
