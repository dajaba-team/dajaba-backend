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

    /** S3 — 저장한 수정본 번호와 새 version 둘(초안·케이스). 화면은 다음 수정·승인 요청에 case_version 을 그대로 쓴다. */
    public record Saved(String draftNo, int revisionNo, List<StrSection> changedSections, boolean needsVerification,
                        long version, long caseVersion) {
        static Saved from(ReviseStrDraft.Revised r) {
            return new Saved(r.draftNo(), r.revisionNo(), r.changedSections(), r.needsVerification(),
                    r.version(), r.caseVersion());
        }
    }

    /** S4 목록의 한 줄. revised_by 는 표시 이름(내부 users.id 는 내보내지 않는다). 1번은 AI 원문(SYSTEM). */
    public record Item(int revisionNo, List<StrSection> changedSections, String revisedBy, Instant revisedAt,
                       boolean aiOriginal) {
        static Item from(ListStrRevisions.Item i) {
            return new Item(i.revisionNo(), i.changedSections(), i.revisedBy(), i.revisedAt(), i.aiOriginal());
        }
    }

    public record RevisionList(String draftNo, int currentRevisionNo, List<Item> revisions) {
        static RevisionList from(ListStrRevisions.Revisions r) {
            return new RevisionList(r.draftNo(), r.currentRevisionNo(), r.revisions().stream().map(Item::from).toList());
        }
    }

    /** S4 ?revision_no= — 그 수정본의 전·후 본문 전체. 1번은 before 가 []. */
    public record RevisionDetail(String draftNo, Item revision, List<StrDraftDetailResponse.Narrative> before,
                                 List<StrDraftDetailResponse.Narrative> after) {
        static RevisionDetail from(ListStrRevisions.Detail d) {
            return new RevisionDetail(d.draftNo(), Item.from(d.revision()), narrative(d.before()), narrative(d.after()));
        }

        private static List<StrDraftDetailResponse.Narrative> narrative(List<NarrativeSection> sections) {
            return sections.stream().map(StrDraftDetailResponse.Narrative::from).toList();
        }
    }
}
