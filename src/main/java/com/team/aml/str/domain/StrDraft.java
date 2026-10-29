package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** STR 초안. 상태는 메서드로만 바뀐다(setter 없음). JPA 매핑은 D4에 붙인다. */
public class StrDraft {

    private final Long caseId;                    // 케이스는 객체가 아니라 번호로만
    private final String generationId;            // AI 생성 번호 (23번)
    private final JsonNode facts;                 // 숫자. 아무도 못 고친다
    private final List<NarrativeSection> aiNarrative; // AI 원문
    private StrDraftStatus status;
    private StrRevision currentRevision;          // 없으면 현재 본문 = AI 원문
    private static final int MAX_SECTION_LENGTH = 5_000;

    private StrDraft(Long caseId, String generationId, JsonNode facts, List<NarrativeSection> aiNarrative) {
        this.caseId = caseId;
        this.generationId = generationId;
        this.facts = facts.deepCopy();
        this.aiNarrative = List.copyOf(aiNarrative);
        this.status = StrDraftStatus.ACTIVE;
        this.currentRevision = null;
    }

    public static StrDraft create(Long caseId, String generationId, JsonNode facts,
                                  List<NarrativeSection> aiNarrative) {
        return new StrDraft(caseId, generationId, facts, aiNarrative);
    }

    public Long getCaseId() { return caseId; }
    public String getGenerationId() { return generationId; }
    public StrDraftStatus getStatus() { return status; }
    public StrRevision getCurrentRevision() { return currentRevision; }
    public JsonNode getFacts() { return facts.deepCopy(); } // 사실값. 복사본을 돌려준다 — 밖에서 바꿔도 초안의 숫자는 그대로.

    /**
     * 본문 수정. 고친 단락(단락 이름 → 새 문장)과 수정자를 받아 새 수정본을 만든다.
     * facts는 인자에 없다 — 숫자는 못 고친다.
     */
    public StrRevision revise(Map<StrSection, String> editedTexts, Long revisedBy) {
        if(status != StrDraftStatus.ACTIVE){
            throw new DraftLockedException(status);
        }
        validateTexts(editedTexts);                 // ← 맨 먼저. 통과해야 아래로 간다

        int nextNo = (currentRevision == null) ? 1 : currentRevision.getRevisionNo() + 1;
        List<NarrativeSection> before = currentBody();
        List<NarrativeSection> after = applyEdits(before, editedTexts);

        StrRevision revision = new StrRevision(
                nextNo, before, after,
                editedTexts.keySet().stream().sorted().toList(),
                revisedBy);

        this.currentRevision = revision;
        return revision;
    }

    /** 고친 문장마다: 비었거나(공백만 포함) 5,000자를 넘으면 거부. */
    private static void validateTexts(Map<StrSection, String> editedTexts) {
        editedTexts.forEach((section, text) -> {
            if (text == null || text.isBlank()) {
                throw new InvalidSectionTextException(section, "문장이 비어 있습니다");
            }
            if (text.length() > MAX_SECTION_LENGTH) {
                throw new InvalidSectionTextException(section, MAX_SECTION_LENGTH + "자를 넘습니다");
            }
        });
    }
    /** lock이면 거부. */
    public void lock() {
        requireActive();
        this.status = StrDraftStatus.LOCKED;
    }

    /**
     * 다른 초안으로 대체될 때 부른다.
     * 같은 케이스에서 초안을 다시 만들었거나, 초안이 있는 케이스가 종료될 때.
     */
    public void supersede() {
        requireActive();
        this.status = StrDraftStatus.SUPERSEDED;
    }

    /** 금지 전이 상태 방지 */
    public void requireActive(){
        if (status != StrDraftStatus.ACTIVE){
            throw new DraftLockedException(status);
        }
    }

    /** 지금 본문: 수정본이 없으면 AI 원문, 있으면 현재 수정본의 "고친 후". */
    public List<NarrativeSection> currentBody() {
        return (currentRevision == null) ? aiNarrative : currentRevision.getAfter();
    }

    /** before를 복사하되, 고친 단락은 문장(text)만 새 것으로 바꾼다. 근거 번호는 그대로. */
    private static List<NarrativeSection> applyEdits(List<NarrativeSection> before,
                                                     Map<StrSection, String> editedTexts) {
        return before.stream()
                .map(s -> editedTexts.containsKey(s.section())
                        ? new NarrativeSection(s.section(), editedTexts.get(s.section()), s.evidenceIds())
                        : s)
                .toList();
    }


}
