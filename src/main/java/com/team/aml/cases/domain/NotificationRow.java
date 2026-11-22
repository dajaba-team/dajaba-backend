package com.team.aml.cases.domain;

import java.time.Instant;

/**
 * 알림 쿼리 한 줄 — 받는 사람으로 이미 거른 이력 (SCRUM-355). comment 는 읽지 않는다(반려 코멘트·메모를 내보내지 않게).
 * receivedByMe 는 재배정에서 내가 받은 사람인지(아니면 넘긴 사람). currentDraftId 는 케이스의 지금 초안.
 */
public record NotificationRow(CaseHistoryAction action, boolean receivedByMe, String caseNo, Long currentDraftId,
                              String reasonCode, long createdBy, Instant createdAt) {
}
