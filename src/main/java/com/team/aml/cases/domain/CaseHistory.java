package com.team.aml.cases.domain;

/**
 * 케이스 이력 한 행. 만들면 바뀌지 않는다 (추가 전용, V006 에서 UPDATE·DELETE 회수).
 * Case 의 업무 메서드만 만든다. JPA 매핑(CreatedOnlyEntity)은 V006 머지 뒤에 붙인다.
 */
public class CaseHistory {

    private final CaseHistoryAction action;
    private final CaseStatus fromStatus;       // 생성(OPEN) 행만 null
    private final CaseStatus toStatus;
    private final String reasonCode;           // REJECT 의 반려 코드, CLOSE 의 종결 사유
    private final String comment;
    private final long caseVersionBefore;      // 이 변경 직전의 cases.version
    private final Long createdBy;

    CaseHistory(CaseHistoryAction action, CaseStatus fromStatus, CaseStatus toStatus,
                String reasonCode, String comment, long caseVersionBefore, Long createdBy) {
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.caseVersionBefore = caseVersionBefore;
        this.createdBy = createdBy;
    }

    public CaseHistoryAction getAction() { return action; }
    public CaseStatus getFromStatus() { return fromStatus; }
    public CaseStatus getToStatus() { return toStatus; }
    public String getReasonCode() { return reasonCode; }
    public String getComment() { return comment; }
    public long getCaseVersionBefore() { return caseVersionBefore; }
    public Long getCreatedBy() { return createdBy; }
}
