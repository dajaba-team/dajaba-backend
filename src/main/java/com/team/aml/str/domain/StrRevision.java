package com.team.aml.str.domain;

import com.team.aml.user.domain.User;

import java.util.List;

/** 수정본. 조사역이 저장 버튼을 한 번 누른 기록. 만들면 바뀌지 않는다. */
public class StrRevision {

    private final int revisionNo;                  // 초안 안의 순번 1, 2, 3...
    private final List<NarrativeSection> before;   // 고치기 전 본문 (전체)
    private final List<NarrativeSection> after;    // 고친 후 본문 (전체)
    private final List<StrSection> editedSections; // 이번에 고친 단락
    private final Long revisedBy;                  // 고친 사람(조사역 번호)
    static final int AI_ORIGINAL_NO = 1;

    StrRevision(int revisionNo,
                List<NarrativeSection> before,
                List<NarrativeSection> after,
                List<StrSection> editedSections, Long revisedBy) {
        this.revisionNo = revisionNo;
        this.before = List.copyOf(before);
        this.after = List.copyOf(after);
        this.editedSections = List.copyOf(editedSections);
        this.revisedBy = revisedBy;
    }

    public int getRevisionNo() { return revisionNo; }
    public List<NarrativeSection> getBefore() { return before; }
    public List<NarrativeSection> getAfter() { return after; }
    public List<StrSection> getEditedSections() { return editedSections; }
    public Long getRevisedBy() { return revisedBy; }

    /** 수정본 1번 = AI 원문. 고치기 전은 없음, 고친 단락도 없음, 작성자는 SYSTEM. */
    static StrRevision aiOriginal(List<NarrativeSection> aiNarrative) {
        return new StrRevision(AI_ORIGINAL_NO, List.of(), aiNarrative, List.of(), User.SYSTEM_ID);
    }

    /** 다음 수정본 번호. */
    int nextNo() { return revisionNo + 1; }

    public boolean isAiOriginal() { return revisionNo == AI_ORIGINAL_NO; }
}
