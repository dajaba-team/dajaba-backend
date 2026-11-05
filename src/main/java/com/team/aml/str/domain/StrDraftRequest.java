package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.shared.domain.AssignedIdEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * 초안 요청 기록 (str_draft_requests). AI 를 부르기 전에 짧은 트랜잭션으로 커밋한다 (미결 21).
 * id = draft_id — 같은 번호로 str_drafts 행이 생긴다. 재전송은 이 번호와 request_snapshot 을 그대로 다시 보낸다.
 */
@Entity
@Table(name = "str_draft_requests")
@AttributeOverride(name = "id", column = @Column(name = "draft_id"))
public class StrDraftRequest extends AssignedIdEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StrDraftRequestStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, updatable = false)
    private JsonNode requestSnapshot;              // AI 에 보낸 요청 그대로

    @Column(name = "finished_at")
    private Instant finishedAt;                    // REQUESTED 가 아닌 상태로 바뀐 시각

    /** JPA 용. */
    protected StrDraftRequest() {}

    private StrDraftRequest(Long draftId, Long caseId, JsonNode requestSnapshot) {
        super(draftId);
        this.caseId = caseId;
        this.requestSnapshot = requestSnapshot.deepCopy();
        this.status = StrDraftRequestStatus.REQUESTED;
    }

    /** draftId 는 nextDraftId() 로 받은 번호. */
    public static StrDraftRequest request(Long draftId, Long caseId, JsonNode requestSnapshot) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        return new StrDraftRequest(draftId, caseId, requestSnapshot);
    }

    public void succeed(Instant at) { finish(StrDraftRequestStatus.SUCCEEDED, at); }
    public void fail(Instant at) { finish(StrDraftRequestStatus.FAILED, at); }
    public void expire(Instant at) { finish(StrDraftRequestStatus.EXPIRED, at); }

    /** REQUESTED 에서만 한 번. */
    private void finish(StrDraftRequestStatus next, Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != StrDraftRequestStatus.REQUESTED) {
            throw new IllegalStateException("이미 끝난 요청이다: " + status + " → " + next);
        }
        this.status = next;
        this.finishedAt = at;
    }

    public boolean isRequested() { return status == StrDraftRequestStatus.REQUESTED; }

    public Long getCaseId() { return caseId; }
    public StrDraftRequestStatus getStatus() { return status; }
    public Instant getFinishedAt() { return finishedAt; }
    public JsonNode getRequestSnapshot() { return requestSnapshot.deepCopy(); }

}
