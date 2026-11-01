package com.team.aml.cases.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 조사 케이스. 상태는 업무 메서드로만 바뀐다(setter 없음). 바뀔 때마다 이력이 한 행 붙는다.
 * 전이 가능 여부는 CaseStatus 가 판단하고, 여기서는 담당자·초안·반려 코드·이력을 함께 바꾼다.
 * 누가 부를 수 있는지(담당자 본인·APPROVER·ADMIN)는 application 에서 CurrentUser 로 검사한다.
 * JPA 매핑·@Version 은 V006 머지 뒤에 붙인다.
 */
public class Case {

    private static final Set<CaseStatus> REASSIGNABLE =
            EnumSet.of(CaseStatus.REVIEWING, CaseStatus.ESCALATED, CaseStatus.DRAFTED, CaseStatus.REJECTED);

    private final String caseNo;                      // CASE-2022-000057
    private final Long alertId;                       // 경보는 객체가 아니라 번호로만
    private CaseStatus status;
    private Long assigneeId;                          // 담당 조사역 (users.id)
    private Long currentDraftId;                      // str 초안은 번호로만 (cases → str 의존 금지)
    private RejectionReasonCode rejectionReasonCode;  // REJECTED 일 때만 있음 (V006 CHECK)
    private CloseReason closeReason;
    private long version;                             // @Version 이 붙으면 커밋 때 올라간다. 도메인은 읽기만
    private final List<CaseHistory> histories = new ArrayList<>();

    private Case(String caseNo, Long alertId) {
        this.caseNo = caseNo;
        this.alertId = alertId;
        this.status = CaseStatus.OPEN;
    }

    /** 케이스 생성. 생성 배치(OpenCasesFromAlerts)가 CasePolicy 로 HIGH 를 확인한 뒤 부른다. */
    public static Case open(String caseNo, Long alertId, Long actorId) {
        Objects.requireNonNull(caseNo, "caseNo");
        Objects.requireNonNull(alertId, "alertId");
        Case c = new Case(caseNo, alertId);
        c.record(CaseHistoryAction.OPEN, null, CaseStatus.OPEN, null, null, actorId);
        return c;
    }

    /** 최초 배정 OPEN → REVIEWING. 활성 조사역인지는 application 이 확인한다. */
    public void assign(Long assigneeId, Long actorId) {
        Objects.requireNonNull(assigneeId, "assigneeId");
        moveTo(CaseStatus.REVIEWING, CaseHistoryAction.ASSIGN, null, null, actorId);
        this.assigneeId = assigneeId;
    }

    /**
     * 담당자 변경. 상태·반려 코드는 그대로.
     * @return 바뀌었으면 true. 같은 담당자면 이력 없이 false.
     */
    public boolean reassign(Long newAssigneeId, Long actorId, String comment) {
        Objects.requireNonNull(newAssigneeId, "newAssigneeId");
        if (!REASSIGNABLE.contains(status)) {
            throw new InvalidCaseTransitionException(status, status, rejectionReasonCode);
        }
        if (newAssigneeId.equals(assigneeId)) {
            return false;
        }
        this.assigneeId = newAssigneeId;
        record(CaseHistoryAction.REASSIGN, status, status, null, comment, actorId);
        return true;
    }

    /** 상신 REVIEWING → ESCALATED. AI 호출 없음, 초안 생성은 별도 요청. */
    public void escalate(Long actorId, String comment) {
        status.validateTransition(CaseStatus.ESCALATED, rejectionReasonCode);
        requireComment(comment, CaseHistoryAction.ESCALATE);
        moveTo(CaseStatus.ESCALATED, CaseHistoryAction.ESCALATE, null, comment, actorId);
    }

    /**
     * 초안 연결·재생성 → DRAFTED. ESCALATED, DRAFTED(재생성), REJECTED+NEED_REVISION 에서만.
     * str 이 초안 저장과 같은 트랜잭션에서 부른다(AttachDraftToCase). 이전 초안 SUPERSEDED 는 str 몫.
     */
    public void attachDraft(Long draftId, Long actorId) {
        Objects.requireNonNull(draftId, "draftId");
        moveTo(CaseStatus.DRAFTED, CaseHistoryAction.ATTACH_DRAFT, null, null, actorId);
        this.currentDraftId = draftId;
        this.rejectionReasonCode = null;
    }

    /** 보완 반려 뒤 같은 초안의 수정본 저장 → DRAFTED. REJECTED+NEED_REVISION 에서만. */
    public void reopenDraft(Long actorId) {
        // ESCALATED·DRAFTED → DRAFTED 도 enum 상으론 허용이라 여기서 한 번 더 막는다
        if (status != CaseStatus.REJECTED || rejectionReasonCode != RejectionReasonCode.NEED_REVISION) {
            throw new InvalidCaseTransitionException(status, CaseStatus.DRAFTED, rejectionReasonCode);
        }
        moveTo(CaseStatus.DRAFTED, CaseHistoryAction.REOPEN_DRAFT, null, null, actorId);
        this.rejectionReasonCode = null;
    }

    /** 승인 요청 DRAFTED → PENDING_APPROVAL. 현재 본문 검증 통과 여부는 str ApprovalGate 가 먼저 본다. */
    public void submitForApproval(Long actorId) {
        moveTo(CaseStatus.PENDING_APPROVAL, CaseHistoryAction.SUBMIT_FOR_APPROVAL, null, null, actorId);
    }

    /** 승인 PENDING_APPROVAL → APPROVED. 담당자 본인은 승인할 수 없다. */
    public void approve(Long approverId) {
        Objects.requireNonNull(approverId, "approverId");
        status.validateTransition(CaseStatus.APPROVED, rejectionReasonCode);
        if (approverId.equals(assigneeId)) {
            throw new SelfApprovalException(caseNo);
        }
        moveTo(CaseStatus.APPROVED, CaseHistoryAction.APPROVE, null, null, approverId);
    }

    /** 반려 PENDING_APPROVAL → REJECTED. 코드·사유 필수. */
    public void reject(Long approverId, RejectionReasonCode code, String comment) {
        Objects.requireNonNull(code, "rejectionReasonCode");
        status.validateTransition(CaseStatus.REJECTED, rejectionReasonCode);
        requireComment(comment, CaseHistoryAction.REJECT);
        moveTo(CaseStatus.REJECTED, CaseHistoryAction.REJECT, code.name(), comment, approverId);
        this.rejectionReasonCode = code;
    }

    /**
     * 종결 → CLOSED. REVIEWING·ESCALATED·DRAFTED, REJECTED+NOT_REPORTABLE 에서만. 사유 필수.
     * 초안이 있는 종결은 str 종결 유스케이스가 초안 SUPERSEDED 와 같은 트랜잭션에서 부른다
     * (직접 종결 API 의 CLOSE_REQUIRES_STR 검사는 application 몫).
     */
    public void close(Long actorId, CloseReason reason, String comment) {
        Objects.requireNonNull(reason, "closeReason");
        status.validateTransition(CaseStatus.CLOSED, rejectionReasonCode);
        requireComment(comment, CaseHistoryAction.CLOSE);
        moveTo(CaseStatus.CLOSED, CaseHistoryAction.CLOSE, reason.name(), comment, actorId);
        this.closeReason = reason;
        this.rejectionReasonCode = null;   // 과거 반려 코드는 REJECT 이력에 남아 있다
    }

    /** 전이 검사 → 상태 변경 → 이력. 검사에 걸리면 아무것도 바뀌지 않는다. */
    private void moveTo(CaseStatus target, CaseHistoryAction action, String reasonCode, String comment, Long actorId) {
        status.validateTransition(target, rejectionReasonCode);
        CaseStatus from = status;
        this.status = target;
        record(action, from, target, reasonCode, comment, actorId);
    }

    private void record(CaseHistoryAction action, CaseStatus from, CaseStatus to,
                        String reasonCode, String comment, Long actorId) {
        histories.add(new CaseHistory(action, from, to, reasonCode, comment, version, actorId));
    }

    private static void requireComment(String comment, CaseHistoryAction action) {
        if (comment == null || comment.isBlank()) {
            throw new CommentRequiredException(action);
        }
    }

    public String getCaseNo() { return caseNo; }
    public Long getAlertId() { return alertId; }
    public CaseStatus getStatus() { return status; }
    public Long getAssigneeId() { return assigneeId; }
    public Long getCurrentDraftId() { return currentDraftId; }
    public RejectionReasonCode getRejectionReasonCode() { return rejectionReasonCode; }
    public CloseReason getCloseReason() { return closeReason; }
    public long getVersion() { return version; }
    public List<CaseHistory> getHistories() { return Collections.unmodifiableList(histories); }
}
