package com.team.aml.cases.application;

import java.time.Instant;

/**
 * 승인 대기 케이스 한 줄 — ListCasesPendingApproval 결과 (str 승인함 P1, SCRUM-222).
 *
 * @param version        지금 cases.version — 승인·반려 요청의 expectedVersion 으로 쓴다
 * @param currentDraftId 승인 대기면 늘 있다 (V006 cases_current_draft_ck)
 * @param assignee       담당 조사역
 * @param submittedAt    마지막 승인 요청(SUBMIT_FOR_APPROVAL 이력) 시각. 보완 뒤 다시 요청하면 새 시각
 */
public record PendingCase(long caseId, String caseNo, long version, long currentDraftId, UserRef assignee,
                          Instant submittedAt) {
}
