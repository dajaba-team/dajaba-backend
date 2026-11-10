package com.team.aml.str.api;

import com.team.aml.str.application.ListStrRevisions;
import com.team.aml.str.application.ReviseStrDraft;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrSection;

import java.time.Instant;
import java.util.List;

/** S3·S4 응답 모양. 단락은 S2 와 같은 {section, label, text, cites, evidence_ids}. */
public final class StrRevisionResponses {

    private StrRevisionResponses() {}

    /**
     * S3 (#91 리뷰, 계약서 S3) — edited_by(표시 이름)·edited_at·narrative(저장된 본문 전체, S2 모양).
     * case_version 은 계약서에 없지만 다음 수정·승인 요청에 필요해 같이 준다(진희 님이 계약서에 추가 예정).
     */
    public record Saved(String draftNo, int revisionNo, List<StrSection> changedSections, String editedBy,
                        Instant editedAt, List<StrDraftDetailResponse.Narrative> narrative, boolean needsVerification,
                        long version, long caseVersion) {
        public static Saved from(ReviseStrDraft.Revised r) {
            return new Saved(r.draftNo(), r.revisionNo(), r.changedSections(), r.editedBy(), r.editedAt(),
                    narrative(r.narrative()), r.needsVerification(), r.version(), r.caseVersion());
        }
    }

    /** S4 목록의 한 줄. edited_by 는 표시 이름(내부 users.id 는 내보내지 않는다). 1번은 AI 원문(SYSTEM). */
    public record Item(int revisionNo, List<StrSection> changedSections, String editedBy, Instant editedAt,
                       boolean aiOriginal) {
        public static Item from(ListStrRevisions.Item i) {
            return new Item(i.revisionNo(), i.changedSections(), i.editedBy(), i.editedAt(), i.aiOriginal());
        }
    }

    /** S4 목록 — 프론트·계약서가 읽는 이름은 items. */
    public record RevisionList(String draftNo, int currentRevisionNo, List<Item> items) {
        public static RevisionList from(ListStrRevisions.Revisions r) {
            return new RevisionList(r.draftNo(), r.currentRevisionNo(), r.items().stream().map(Item::from).toList());
        }
    }

    /** S4 ?revision_no= — 평평한 모양(#91 리뷰). 그 수정본의 전·후 본문 전체, 1번은 narrative_before 가 []. */
    public record RevisionDetail(String draftNo, int revisionNo, List<StrSection> changedSections, String editedBy,
                                 Instant editedAt, boolean aiOriginal,
                                 List<StrDraftDetailResponse.Narrative> narrativeBefore,
                                 List<StrDraftDetailResponse.Narrative> narrativeAfter) {
        public static RevisionDetail from(ListStrRevisions.Detail d) {
            ListStrRevisions.Item i = d.revision();
            return new RevisionDetail(d.draftNo(), i.revisionNo(), i.changedSections(), i.editedBy(), i.editedAt(),
                    i.aiOriginal(), narrative(d.before()), narrative(d.after()));
        }
    }

    private static List<StrDraftDetailResponse.Narrative> narrative(List<NarrativeSection> sections) {
        return sections.stream().map(StrDraftDetailResponse.Narrative::from).toList();
    }
}
