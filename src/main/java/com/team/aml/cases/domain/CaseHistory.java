package com.team.aml.cases.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 케이스 이력 한 행 (ops.case_history, V006). 만들면 바뀌지 않는다 — setter·UPDATE 없음, DB 에서 UPDATE·DELETE 회수.
 * Case 의 업무 메서드만 만든다.
 *
 * CreatedOnlyEntity 를 상속하지 않는다: created_by 는 "지금 로그인한 사람"이 아니라 업무상 행위자다
 * (승인은 승인자, 생성 배치는 SYSTEM). 업무 메서드가 받은 actorId 를 그대로 넣는다. AccessLog·AiCallLog 와 같은 모양.
 */
@Entity
@Table(name = "case_history")
public class CaseHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;                       // Case.takePendingHistories() 가 붙인다

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private CaseHistoryAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 32)
    private CaseStatus fromStatus;             // 생성(OPEN) 행만 null

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 32)
    private CaseStatus toStatus;

    @Column(name = "reason_code", updatable = false, length = 32)
    private String reasonCode;                 // REJECT 의 반려 코드, CLOSE 의 종결 사유

    @Column(name = "comment", updatable = false, columnDefinition = "text")
    private String comment;

    @Column(name = "case_version_before", nullable = false, updatable = false)
    private long caseVersionBefore;            // 이 변경 직전의 cases.version

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "from_assignee_id", updatable = false)
    private Long fromAssigneeId;               // REASSIGN 에서 넘긴 사람 (V010). 그 밖엔 null

    @Column(name = "to_assignee_id", updatable = false)
    private Long toAssigneeId;                 // ASSIGN·REASSIGN 에서 받은 사람 (V010). 자기 승인 판정에 쓴다

    /** JPA 용. */
    protected CaseHistory() {}

    CaseHistory(CaseHistoryAction action, CaseStatus fromStatus, CaseStatus toStatus,
                String reasonCode, String comment, long caseVersionBefore, Long createdBy) {
        this(action, fromStatus, toStatus, reasonCode, comment, caseVersionBefore, createdBy, null, null);
    }

    /** 배정(ASSIGN: to 만)·재배정(REASSIGN: from·to) 이력. 다른 행동은 둘 다 null — V010 CHECK 와 같은 규칙. */
    CaseHistory(CaseHistoryAction action, CaseStatus fromStatus, CaseStatus toStatus,
                String reasonCode, String comment, long caseVersionBefore, Long createdBy,
                Long fromAssigneeId, Long toAssigneeId) {
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.caseVersionBefore = caseVersionBefore;
        this.createdBy = createdBy;
        this.fromAssigneeId = fromAssigneeId;
        this.toAssigneeId = toAssigneeId;
        this.createdAt = Instant.now();
    }

    /** Case.takePendingHistories() 만 부른다. 한 번 붙으면 바꾸지 않는다. */
    void attachTo(Long caseId) {
        if (this.caseId != null && !this.caseId.equals(caseId)) {
            throw new IllegalStateException("이미 다른 케이스의 이력입니다");
        }
        this.caseId = caseId;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public CaseHistoryAction getAction() { return action; }
    public CaseStatus getFromStatus() { return fromStatus; }
    public CaseStatus getToStatus() { return toStatus; }
    public String getReasonCode() { return reasonCode; }
    public String getComment() { return comment; }
    public long getCaseVersionBefore() { return caseVersionBefore; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
    public Long getFromAssigneeId() { return fromAssigneeId; }
    public Long getToAssigneeId() { return toAssigneeId; }
}
