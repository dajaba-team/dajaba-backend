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

    // 상태가 그대로인 동작이 허용되는 상태. 전이 표(NEXT)와 같은 곳에 둔다 (3주차 리뷰 질문 2).
    private static final Set<CaseStatus> REASSIGNABLE = Set.of(REVIEWING, ESCALATED, DRAFTED, REJECTED);
    // 메모·요약 편집 — 여기에 REJECTED+NEED_REVISION 이 더해진다 (CASES 명세 §2.3)
    private static final Set<CaseStatus> EDITABLE = Set.of(REVIEWING, ESCALATED, DRAFTED);
    // 진행 중 — 담당 조사역이 아직 손에 쥐고 있는 상태 (미결 33, SCRUM-178). 계정 목록 건수·역할 변경 검사가 이것만 쓴다.
    // OPEN(담당 없음)·APPROVED·REPORTED·CLOSED 는 세지 않는다.
    private static final Set<CaseStatus> ACTIVE = Set.of(REVIEWING, ESCALATED, DRAFTED, PENDING_APPROVAL, REJECTED);

    /** 진행 중 상태 집합 (미결 33). 이 정의는 여기 한 곳에만 둔다. */
    public static Set<CaseStatus> active() {
        return ACTIVE;
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    /**
     * 이 상태(와 반려 코드)에서 그 동작을 할 수 있는가. 상태별 허용 동작은 이 한 곳에서만 판단한다(SCRUM-171).
     * 상태를 바꾸는 동작은 전이 표(NEXT)를 그대로 쓰고, 상태가 그대로인 동작(재배정·메모·요약)은 위 집합을 쓴다.
     * 누가(역할·담당자)·초안이 있는지는 여기서 보지 않는다 — CaseAction.availableFor 와 application 몫.
     * rejectionCode 는 지금 cases.rejection_reason_code 값 — REJECTED 에서만 본다.
     */
    public boolean allows(CaseAction action, RejectionReasonCode rejectionCode) {
        return switch (action) {
            case ASSIGN -> canTransitionTo(REVIEWING, rejectionCode);
            case REASSIGN -> REASSIGNABLE.contains(this);
            case UPDATE_NOTES, REFRESH_SUMMARY -> EDITABLE.contains(this)
                    || (this == REJECTED && rejectionCode == RejectionReasonCode.NEED_REVISION);
            case ESCALATE -> canTransitionTo(ESCALATED, rejectionCode);
            case CLOSE, CLOSE_WITH_DRAFT -> canTransitionTo(CLOSED, rejectionCode);
            case GENERATE_DRAFT -> canTransitionTo(DRAFTED, rejectionCode);
        };
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
