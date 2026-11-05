package com.team.aml.str.domain;

import com.team.aml.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 수정본. 조사역이 저장 버튼을 한 번 누른 기록. 만들면 바뀌지 않는다 (str_revisions, 추가만).
 * draft_id 칸은 StrDraft.revisions 가 채운다(@JoinColumn). 이 클래스는 초안을 모른다.
 * created_by 는 auditor 가 아니라 revisedBy 로 직접 넣는다 — 수정본 1번(AI 원문)은 요청한 조사역이 아니라 SYSTEM(1)이어야 해서
 * CreatedOnlyEntity(@CreatedBy)를 상속하지 않는다. created_at 만 auditing 으로 채운다.
 */
@Entity
@Table(name = "str_revisions")
@EntityListeners(AuditingEntityListener.class)
public class StrRevision {

    static final int AI_ORIGINAL_NO = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;                  // 초안 안의 순번 1, 2, 3...

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative_before", nullable = false, updatable = false)
    private List<NarrativeSection> before;   // 고치기 전 본문 (전체)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "narrative_after", nullable = false, updatable = false)
    private List<NarrativeSection> after;    // 고친 후 본문 (전체)

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_sections", nullable = false, updatable = false)
    private String[] changedSections;              // 이번에 고친 단락 이름. 1번은 비어 있음

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long revisedBy;


    /** JPA 용. */
    protected StrRevision() {}

    StrRevision(int revisionNo,
                List<NarrativeSection> before,
                List<NarrativeSection> after,
                List<StrSection> editedSections, Long revisedBy) {
        this.revisionNo = revisionNo;
        this.before = List.copyOf(before);
        this.after = List.copyOf(after);
        this.changedSections = editedSections.stream().map(Enum::name).toArray(String[]::new);
        this.revisedBy = revisedBy;
    }

    /** 수정본 1번 = AI 원문. 고치기 전은 없음, 고친 단락도 없음, 작성자는 SYSTEM. */
    static StrRevision aiOriginal(List<NarrativeSection> aiNarrative) {
        return new StrRevision(AI_ORIGINAL_NO, List.of(), aiNarrative, List.of(), User.SYSTEM_ID);
    }

    /** 다음 수정본 번호. */
    int nextNo() { return revisionNo + 1; }

    public boolean isAiOriginal() { return revisionNo == AI_ORIGINAL_NO; }

    public Long getId() { return id; }
    public int getRevisionNo() { return revisionNo; }
    public List<NarrativeSection> getBefore() { return List.copyOf(before); }   // DB에서 읽은 목록도 밖에서 못 바꾸게
    public List<NarrativeSection> getAfter() { return List.copyOf(after); }
    public List<StrSection> getEditedSections() {
        return Arrays.stream(changedSections).map(StrSection::valueOf).toList();
    }
    public Long getRevisedBy() { return revisedBy; } // 1번은 SYSTEM(1)
    public Instant getCreatedAt() { return createdAt; }
}
