package com.team.aml.cases.domain;

import java.util.Optional;

/**
 * 업무 알림 종류 (SCRUM-355, 노션 「비동기 작업 알림 설계 문서」 6.5). 이력 한 줄이 어떤 알림이 되는지.
 * 누가 받는지는 {@link NotificationRows} 의 쿼리가 고르고, 여기는 이름만 붙인다.
 * 화면 문구는 화면이 type 으로 만든다.
 */
public enum NotificationType {
    CASE_ASSIGNED(false),           // ASSIGN — 받은 사람
    CASE_REASSIGNED_TO_ME(false),   // REASSIGN — 받은 사람
    CASE_REASSIGNED_AWAY(false),    // REASSIGN — 넘긴 사람
    APPROVAL_REQUESTED(true),       // SUBMIT_FOR_APPROVAL — 맡은 적 없는 승인자
    STR_APPROVED(true),             // APPROVE — 지금 담당
    STR_REJECTED(true),             // REJECT — 지금 담당
    STR_REPORTED(true);             // REPORT — 지금 담당만

    private final boolean carriesDraft;

    NotificationType(boolean carriesDraft) {
        this.carriesDraft = carriesDraft;
    }

    /** draft_no 를 주는 종류 — 승인 요청과 STR 3종 (★A 11/22). 케이스 3종은 null. */
    public boolean carriesDraft() {
        return carriesDraft;
    }

    /** 이력 동작 → 알림 종류. 재배정은 내가 받은 사람이면 TO_ME, 아니면(넘긴 사람) AWAY. 알림이 아닌 동작은 empty. */
    public static Optional<NotificationType> of(CaseHistoryAction action, boolean receivedByMe) {
        return Optional.ofNullable(switch (action) {
            case ASSIGN -> CASE_ASSIGNED;
            case REASSIGN -> receivedByMe ? CASE_REASSIGNED_TO_ME : CASE_REASSIGNED_AWAY;
            case SUBMIT_FOR_APPROVAL -> APPROVAL_REQUESTED;
            case APPROVE -> STR_APPROVED;
            case REJECT -> STR_REJECTED;
            case REPORT -> STR_REPORTED;
            case OPEN, NOTE, ESCALATE, CLOSE, ATTACH_DRAFT, REOPEN_DRAFT -> null;
        });
    }
}
