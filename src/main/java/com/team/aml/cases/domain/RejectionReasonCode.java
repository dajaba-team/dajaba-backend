package com.team.aml.cases.domain;

/**
 * 반려 코드. 상태를 늘리지 않고 REJECTED 케이스의 다음 행동을 가른다 (안건 24).
 * cases 에는 지금 반려의 코드만 두고, 과거 코드는 case_history·approvals 에 남는다.
 */
public enum RejectionReasonCode {
    NEED_REVISION(CaseStatus.DRAFTED),   // 보완: 수정본·새 초안 저장만
    NOT_REPORTABLE(CaseStatus.CLOSED);   // 보고 대상 아님: 사유 종결만

    private final CaseStatus allowedNext;

    RejectionReasonCode(CaseStatus allowedNext) {
        this.allowedNext = allowedNext;
    }

    /** REJECTED 에서 이 코드로 갈 수 있는 상태인지. */
    public boolean allows(CaseStatus target) {
        return allowedNext == target;
    }
}
