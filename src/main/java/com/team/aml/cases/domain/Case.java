package com.team.aml.cases.domain;

import com.team.aml.shared.domain.BaseEntity;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 조사 케이스 (ops.cases, V006). 상태는 업무 메서드로만 바뀐다(setter 없음). 바뀔 때마다 이력이 한 행 생긴다.
 * 전이 가능 여부와 상태별 허용 동작은 CaseStatus 가 판단하고, 여기서는 담당자·초안·반려 코드·이력을 함께 바꾼다.
 * 누가 부를 수 있는지(담당자 본인·APPROVER·ADMIN)는 application 에서 CurrentUser 로 검사한다.
 *
 * 이력은 엔티티 컬렉션으로 들고 있지 않는다(3주차 리뷰 질문 2). 업무 메서드가 만든 이력은 저장 전까지
 * pendingHistories 에 쌓이고, application 이 케이스를 저장한 뒤 takePendingHistories() 로 꺼내 CaseHistoryRepository 에 넣는다.
 * 같은 트랜잭션이라 케이스·이력은 함께 커밋되거나 함께 롤백된다.
 *
 * JPQL 에서 CASE 는 예약어라 엔티티 이름을 InvestigationCase 로 둔다 (select c from InvestigationCase c).
 */
@Entity(name = "InvestigationCase")
@Table(name = "cases")
public class Case extends BaseEntity {

    @Column(name = "case_no", nullable = false, updatable = false, length = 32)
    private String caseNo;                            // CASE-2022-000057

    @Column(name = "alert_id", nullable = false, updatable = false)
    private Long alertId;                             // 경보는 객체가 아니라 번호로만 (cases → detection 엔티티 의존 없음)

    @Column(name = "account_key", nullable = false, updatable = false, length = 64)
    private String accountKey;                        // 경보의 계좌 (내부 키 — 화면에는 가린 값만)

    @Column(name = "entity_id", updatable = false, length = 32)
    private String entityId;                          // 법인 미확인이면 null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status;

    @Column(name = "assignee_id")
    private Long assigneeId;                          // 담당 조사역 (users.id)

    @Column(name = "current_draft_id")
    private Long currentDraftId;                      // str 초안은 번호로만 (cases → str 의존 금지)

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason_code", length = 32)
    private RejectionReasonCode rejectionReasonCode;  // REJECTED 일 때만 있음 (V006 CHECK)

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 32)
    private CloseReason closeReason;                  // CLOSED 일 때만 있음 (V006 CHECK)

    @Version
    @Column(name = "version", nullable = false)
    private long version;                             // 커밋 때 Hibernate 가 올린다. 도메인은 읽기만

    @Transient
    private List<CaseHistory> pendingHistories = new ArrayList<>();

    /** JPA 용. */
    protected Case() {}

    private Case(String caseNo, Long alertId, String accountKey, String entityId) {
        this.caseNo = caseNo;
        this.alertId = alertId;
        this.accountKey = accountKey;
        this.entityId = entityId;
        this.status = CaseStatus.OPEN;
    }

    /** 케이스 생성. 생성 배치(OpenCasesFromAlerts)가 CasePolicy 로 HIGH 를 확인한 뒤 부른다. */
    public static Case open(String caseNo, Long alertId, String accountKey, String entityId, Long actorId) {
        Objects.requireNonNull(caseNo, "caseNo");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(accountKey, "accountKey");
        Case c = new Case(caseNo, alertId, accountKey, entityId);
        c.record(CaseHistoryAction.OPEN, null, CaseStatus.OPEN, null, null, actorId);
        return c;
    }

    /** 최초 배정 OPEN → REVIEWING. 활성 조사역인지는 application 이 확인한다. 이력에 받은 사람을 남긴다 (V010). */
    public void assign(Long assigneeId, Long actorId) {
        Objects.requireNonNull(assigneeId, "assigneeId");
        status.validateTransition(CaseStatus.REVIEWING, rejectionReasonCode);
        CaseStatus from = status;
        this.status = CaseStatus.REVIEWING;
        this.assigneeId = assigneeId;
        pendingHistories.add(new CaseHistory(CaseHistoryAction.ASSIGN, from, CaseStatus.REVIEWING,
                null, null, version, actorId, null, assigneeId));
    }

    /**
     * 담당자 변경. 상태·반려 코드는 그대로.
     * @return 바뀌었으면 true. 같은 담당자면 이력 없이 false.
     */
    public boolean reassign(Long newAssigneeId, Long actorId, String comment) {
        Objects.requireNonNull(newAssigneeId, "newAssigneeId");
        if (!status.isReassignable()) {
            throw InvalidCaseTransitionException.notAllowedIn(status, CaseHistoryAction.REASSIGN, rejectionReasonCode);
        }
        if (newAssigneeId.equals(assigneeId)) {
            return false;
        }
        Long previous = this.assigneeId;
        this.assigneeId = newAssigneeId;
        // 넘긴 사람도 남겨야 나중에 그 사람이 승인자가 됐을 때 자기 승인을 막을 수 있다 (V010, 미결 32)
        pendingHistories.add(new CaseHistory(CaseHistoryAction.REASSIGN, status, status,
                null, comment, version, actorId, previous, newAssigneeId));
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

    /**
     * 초안을 붙일 수 있는 상태인지만 본다(아무것도 바꾸지 않음). ESCALATED, DRAFTED, REJECTED+NEED_REVISION.
     * AttachDraftToCase.lock 이 케이스를 잠근 뒤, str 이 초안을 넣기 전에 부른다.
     */
    public void requireCanAttachDraft() {
        status.validateTransition(CaseStatus.DRAFTED, rejectionReasonCode);
    }

    /**
     * 지금 초안의 수정본을 저장할 수 있는지 본다(아무것도 바꾸지 않음).
     * DRAFTED 또는 보완 반려(REJECTED+NEED_REVISION)이고, 현재 초안이 draftId 일 때만.
     * 보완 반려 상태면 부르는 쪽(TouchCaseForRevision)이 이어서 reopenDraft 로 DRAFTED 로 되돌린다 (#43, 채영 확인 A안).
     */
    public void requireRevisable(Long draftId) {
        if (!isRevisableStatus() || !Objects.equals(currentDraftId, draftId)) {
            throw InvalidCaseTransitionException.revisionNotAllowed(status, rejectionReasonCode, draftId, currentDraftId);
        }
    }

    /** 수정본 저장이 가능한 상태인가 — DRAFTED, 또는 보완 반려(REJECTED+NEED_REVISION). */
    private boolean isRevisableStatus() {
        return status == CaseStatus.DRAFTED || isRejectedForRevision();
    }

    /**
     * 담당 조사역이 메모·요약을 고칠 수 있는 상태인가 — REVIEWING·ESCALATED·DRAFTED·보완 반려 (CASES 명세 §2.3, 계약서 5.3).
     * 보고 안 함 반려·승인 대기 이후는 내용 편집 금지.
     */
    public boolean isEditableByAssignee() {
        return status == CaseStatus.REVIEWING || status == CaseStatus.ESCALATED
                || status == CaseStatus.DRAFTED || isRejectedForRevision();
    }

    /** 보완 반려(REJECTED+NEED_REVISION) 상태인가. */
    public boolean isRejectedForRevision() {
        return status == CaseStatus.REJECTED && rejectionReasonCode == RejectionReasonCode.NEED_REVISION;
    }

    /** 보완 반려 뒤 같은 초안의 수정본 저장 → DRAFTED. REJECTED+NEED_REVISION 에서만. */
    public void reopenDraft(Long actorId) {
        // ESCALATED·DRAFTED → DRAFTED 도 enum 상으론 허용이라 여기서 한 번 더 막는다
        if (!isRejectedForRevision()) {
            throw new InvalidCaseTransitionException(status, CaseStatus.DRAFTED, rejectionReasonCode);
        }
        moveTo(CaseStatus.DRAFTED, CaseHistoryAction.REOPEN_DRAFT, null, null, actorId);
        this.rejectionReasonCode = null;
    }

    /** 승인 요청 DRAFTED → PENDING_APPROVAL. 현재 본문 검증 통과 여부는 str ApprovalGate 가 먼저 본다. */
    public void submitForApproval(Long actorId) {
        moveTo(CaseStatus.PENDING_APPROVAL, CaseHistoryAction.SUBMIT_FOR_APPROVAL, null, null, actorId);
    }

    /**
     * 승인 PENDING_APPROVAL → APPROVED. 지금 담당이거나 예전에 담당했던 사람은 승인할 수 없다 (409 SELF_APPROVAL, 미결 32).
     *
     * @param approverWasAssignee 승인자가 이 케이스를 맡은 적이 있는가 — application 이 {@code WasCaseAssignee} 로 이력을 보고 넘긴다.
     *                            케이스는 이력을 들고 있지 않아서(클래스 주석) 여기서 직접 볼 수 없다
     */
    public void approve(Long approverId, boolean approverWasAssignee) {
        Objects.requireNonNull(approverId, "approverId");
        status.validateTransition(CaseStatus.APPROVED, rejectionReasonCode);
        requireNotSelfApproval(approverId, approverWasAssignee);
        moveTo(CaseStatus.APPROVED, CaseHistoryAction.APPROVE, null, null, approverId);
    }

    /**
     * 반려 PENDING_APPROVAL → REJECTED. 코드·사유 필수. 승인과 같이 지금·예전 담당은 반려할 수 없다 (409 SELF_APPROVAL).
     *
     * @param approverWasAssignee {@link #approve} 와 같다
     */
    public void reject(Long approverId, boolean approverWasAssignee, RejectionReasonCode code, String comment) {
        Objects.requireNonNull(approverId, "approverId");
        Objects.requireNonNull(code, "rejectionReasonCode");
        status.validateTransition(CaseStatus.REJECTED, rejectionReasonCode);
        requireNotSelfApproval(approverId, approverWasAssignee);
        requireComment(comment, CaseHistoryAction.REJECT);
        moveTo(CaseStatus.REJECTED, CaseHistoryAction.REJECT, code.name(), comment, approverId);
        this.rejectionReasonCode = code;
    }

    /**
     * 종결 → CLOSED. REVIEWING·ESCALATED·DRAFTED, REJECTED+NOT_REPORTABLE 에서만. 사유 필수.
     * 초안이 있는 종결은 str 종결 유스케이스가 초안 SUPERSEDED 와 같은 트랜잭션에서 부른다.
     * 직접 종결 API(초안 없음)는 closeWithoutDraft 를 쓴다.
     */
    public void close(Long actorId, CloseReason reason, String comment) {
        Objects.requireNonNull(reason, "closeReason");
        status.validateTransition(CaseStatus.CLOSED, rejectionReasonCode);
        requireComment(comment, CaseHistoryAction.CLOSE);
        moveTo(CaseStatus.CLOSED, CaseHistoryAction.CLOSE, reason.name(), comment, actorId);
        this.closeReason = reason;
        this.rejectionReasonCode = null;   // 과거 반려 코드는 REJECT 이력에 남아 있다
    }

    /**
     * 초안 없는 종결 (POST /cases/{case_no}/close, API-06). 닫을 수 없는 상태면 409 INVALID_CASE_TRANSITION,
     * 닫을 수는 있지만 초안이 있으면 409 CLOSE_REQUIRES_STR — 화면이 close-with-draft 로 가게 한다.
     */
    public void closeWithoutDraft(Long actorId, CloseReason reason, String comment) {
        status.validateTransition(CaseStatus.CLOSED, rejectionReasonCode);
        if (currentDraftId != null) {
            throw new CloseRequiresStrException(caseNo);
        }
        close(actorId, reason, comment);
    }

    /**
     * 아직 저장하지 않은 이력을 꺼낸다. 케이스를 저장해 id 가 생긴 뒤에 부른다.
     * 꺼낸 이력에는 케이스 id 가 붙고, 이 케이스의 대기 목록은 비워진다.
     */
    public List<CaseHistory> takePendingHistories() {
        if (getId() == null) {
            throw new IllegalStateException("케이스를 먼저 저장해야 이력에 case_id 를 붙일 수 있습니다: " + caseNo);
        }
        List<CaseHistory> taken = new ArrayList<>(pendingHistories);
        taken.forEach(h -> h.attachTo(getId()));
        pendingHistories.clear();
        return taken;
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
        pendingHistories.add(new CaseHistory(action, from, to, reasonCode, comment, version, actorId));
    }

    private void requireNotSelfApproval(Long approverId, boolean approverWasAssignee) {
        if (approverWasAssignee || approverId.equals(assigneeId)) {
            throw new SelfApprovalException(caseNo);
        }
    }

    private static void requireComment(String comment, CaseHistoryAction action) {
        if (comment == null || comment.isBlank()) {
            throw new CommentRequiredException(action);
        }
    }

    public String getCaseNo() { return caseNo; }
    public Long getAlertId() { return alertId; }
    public String getAccountKey() { return accountKey; }
    public String getEntityId() { return entityId; }
    public CaseStatus getStatus() { return status; }
    public Long getAssigneeId() { return assigneeId; }
    public Long getCurrentDraftId() { return currentDraftId; }
    public RejectionReasonCode getRejectionReasonCode() { return rejectionReasonCode; }
    public CloseReason getCloseReason() { return closeReason; }
    public long getVersion() { return version; }
    /** 저장 전 이력(읽기 전용). 저장된 이력은 CaseHistoryRepository 로 조회한다. */
    public List<CaseHistory> getPendingHistories() { return Collections.unmodifiableList(pendingHistories); }
}
