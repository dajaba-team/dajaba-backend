package com.team.aml.str.domain;

import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.shared.domain.CreatedOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * 승인·반려 기록 한 줄 (ops.approvals, V009 — 추가만). "어느 초안의 어떤 본문(수정본·해시)을, 어느 검증을 근거로, 어떻게 결정했나".
 * 만들면 바뀌지 않는다 — setter 없음, V009 가 UPDATE·DELETE 권한 회수. created_by = 승인자(auditing).
 * 반려 당시 해시(narrative_sha256)는 보완 요청 뒤 재요청 때 ApprovalGate 가 NARRATIVE_UNCHANGED 를 판단하는 기준이다 (str.md 게이트 4번).
 * 반려 코드 값은 cases 와 같은 RejectionReasonCode — 같은 트랜잭션에서 cases 에도 남는다 (안건 24).
 */
@Entity
@Table(name = "approvals")
public class Approval extends CreatedOnlyEntity {

    static final int MAX_COMMENT_LENGTH = 2_000;   // V009 CHECK char_length(comment) <= 2000

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;                 // 승인·반려 당시 현재 수정본 (str_revisions.id)

    @Column(name = "verification_result_id", updatable = false)
    private Long verificationResultId;       // 승인 근거 검증 (verification_results.id). 승인이면 필수, 반려면 null

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 16)
    private ApprovalDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", updatable = false, length = 32)
    private RejectionReasonCode reasonCode;  // 반려만

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "narrative_sha256", nullable = false, updatable = false, length = 64)
    private String narrativeSha256;          // 결정 당시 본문 해시

    @Column(name = "comment", updatable = false, columnDefinition = "text")   // V009 text — validate 가 varchar 로 보지 않게 (CaseHistory 와 같음)
    private String comment;                  // 반려 사유 (1~2,000자)

    /** JPA 용. */
    protected Approval() {}

    private Approval(StrDraft draft, ApprovalDecision decision) {
        this.draftId = draft.getId();
        this.revisionId = draft.getCurrentRevision().getId();   // 저장된 수정본이어야 한다 (V009 복합 FK)
        this.narrativeSha256 = NarrativeHash.of(draft.currentBody());
        this.decision = decision;
    }

    /** 승인 (P2). 근거 검증은 ApprovalGate 가 고른 "지금 본문의 최신 PASSED 검증". */
    public static Approval approved(StrDraft draft, VerificationResult verification) {
        Objects.requireNonNull(verification, "verification");
        if (!verification.isPassed()) {
            throw new IllegalArgumentException("통과하지 않은 검증으로 승인할 수 없다: " + verification.getStatus());
        }
        Approval a = new Approval(draft, ApprovalDecision.APPROVED);
        a.verificationResultId = verification.getId();
        return a;
    }

    /** 반려 (P3). 코드·사유 둘 다 필수 — api 가 400 으로 먼저 막고, 여기는 안전장치. */
    public static Approval rejected(StrDraft draft, RejectionReasonCode reasonCode, String comment) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (comment == null || comment.isBlank() || comment.codePointCount(0, comment.length()) > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("반려 사유는 1~" + MAX_COMMENT_LENGTH + "자여야 한다");
        }
        Approval a = new Approval(draft, ApprovalDecision.REJECTED);
        a.reasonCode = reasonCode;
        a.comment = comment;
        return a;
    }

    /** 보완 요청 반려인가 — 이때만 재요청 때 본문이 바뀌었는지 본다. */
    public boolean isNeedRevision() {
        return decision == ApprovalDecision.REJECTED && reasonCode == RejectionReasonCode.NEED_REVISION;
    }

    public Long getDraftId() { return draftId; }
    public Long getRevisionId() { return revisionId; }
    public Long getVerificationResultId() { return verificationResultId; }
    public ApprovalDecision getDecision() { return decision; }
    public RejectionReasonCode getReasonCode() { return reasonCode; }
    public String getNarrativeSha256() { return narrativeSha256; }
    public String getComment() { return comment; }
}
