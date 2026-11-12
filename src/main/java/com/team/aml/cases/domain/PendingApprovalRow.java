package com.team.aml.cases.domain;

import java.time.Instant;

/**
 * 승인 대기 케이스 한 줄 — CaseRepository.findPendingApproval 결과 (SCRUM-222).
 * submittedAt 은 그 케이스의 마지막 SUBMIT_FOR_APPROVAL 이력 시각.
 */
public record PendingApprovalRow(Long caseId, String caseNo, long version, Long currentDraftId, Long assigneeId,
                                 Instant submittedAt) {}
