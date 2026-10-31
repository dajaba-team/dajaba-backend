package com.team.aml.cases.domain;

/** case_history.action. V006 CHECK 와 같은 목록. */
public enum CaseHistoryAction {
    OPEN,                 // 케이스 생성 (배치, SYSTEM)
    ASSIGN,               // 최초 배정 OPEN → REVIEWING
    REASSIGN,             // 담당자 변경, 상태 그대로
    NOTE,                 // 메모 전문 저장, 상태 그대로 (API-05에서 사용)
    ESCALATE,             // REVIEWING → ESCALATED
    CLOSE,                // → CLOSED, reason_code 에 종결 사유
    ATTACH_DRAFT,         // 초안 연결·재생성 → DRAFTED
    REOPEN_DRAFT,         // 보완 반려 뒤 같은 초안 수정 → DRAFTED
    SUBMIT_FOR_APPROVAL,  // DRAFTED → PENDING_APPROVAL
    APPROVE,              // → APPROVED
    REJECT                // → REJECTED, reason_code 에 반려 코드
}
