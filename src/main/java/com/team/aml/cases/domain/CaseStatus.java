package com.team.aml.cases.domain;

import java.util.Map;
import java.util.Set;

/**
 * 케이스 상태 9개. 전이 가능 여부는 이 enum 한 곳에서만 판단한다 (docs/06_features/cases.md 전이 표).
 * REPORTED·CLOSED 는 끝 상태 — MVP 에서 재개하지 않는다.
 */
public enum CaseStatus {
    OPEN,              // 생성 완료, 담당자 없음
    REVIEWING,         // 담당 조사역이 조사 중
    ESCALATED,         // STR 작성 단계로 상신
    DRAFTED,           // 초안 연결됨
    PENDING_APPROVAL,  // 승인자 검토 대기
    APPROVED,          // 내부 승인 완료 (기관 제출 완료가 아님)
    REPORTED,          // 모의 제출 완료 (API 12-2, APPROVED → REPORTED)
    REJECTED,          // 반려 — 다음 행동은 RejectionReasonCode 가 가른다
    CLOSED;            // 사유를 남겨 종결

    // 그림의 화살표. REJECTED 의 두 갈래는 반려 코드로 한 번 더 거른다.
    private static final Map<CaseStatus, Set<CaseStatus>> NEXT = Map.of(
            OPEN, Set.of(REVIEWING),
            REVIEWING, Set.of(ESCALATED, CLOSED),
            ESCALATED, Set.of(DRAFTED, CLOSED),
            DRAFTED, Set.of(DRAFTED, PENDING_APPROVAL, CLOSED),
            PENDING_APPROVAL, Set.of(APPROVED, REJECTED),
            APPROVED, Set.of(REPORTED),
            REPORTED, Set.of(),
            REJECTED, Set.of(DRAFTED, CLOSED),
            CLOSED, Set.of());

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    /**
     * 전이 검사. 허용되지 않으면 InvalidCaseTransitionException(409 INVALID_CASE_TRANSITION).
     * rejectionCode 는 지금 cases.rejection_reason_code 값 — REJECTED 에서만 본다.
     */
    public void validateTransition(CaseStatus target, RejectionReasonCode rejectionCode) {
        if (!canTransitionTo(target, rejectionCode)) {
            throw new InvalidCaseTransitionException(this, target, rejectionCode);
        }
    }

    public boolean canTransitionTo(CaseStatus target, RejectionReasonCode rejectionCode) {
        if (target == null || !NEXT.get(this).contains(target)) {
            return false;
        }
        if (this == REJECTED) {
            return rejectionCode != null && rejectionCode.allows(target);
        }
        return true;
    }
}
