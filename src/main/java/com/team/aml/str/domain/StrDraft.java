package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;

/** STR 초안. 상태는 메서드로만 바뀐다(setter 없음). JPA 매핑은 D4에 붙인다. */
public class StrDraft {

    private final Long caseId;                    // 케이스는 객체가 아니라 번호로만
    private final String generationId;            // AI 생성 번호 (23번)
    private final JsonNode facts;                 // 숫자. 아무도 못 고친다
    private StrDraftStatus status;
    private StrRevision currentRevision;
    private static final int MAX_SECTION_LENGTH = 5_000;

    private StrDraft(Long caseId, String generationId, JsonNode facts, List<NarrativeSection> aiNarrative) {
        this.caseId = caseId;
        this.generationId = generationId;
        this.facts = facts.deepCopy();
        this.status = StrDraftStatus.ACTIVE;
        this.currentRevision = StrRevision.aiOriginal(aiNarrative); // 수정본 1번 = AI 원문
    }

    public Long getCaseId() { return caseId; }
    public String getGenerationId() { return generationId; }
    public StrDraftStatus getStatus() { return status; }
    public StrRevision getCurrentRevision() { return currentRevision; }
    public JsonNode getFacts() { return facts.deepCopy(); } // 사실값. 복사본을 돌려준다 — 밖에서 바꿔도 초안의 숫자는 그대로.

    public static StrDraft create(Long caseId, String generationId, JsonNode facts,
                                  List<NarrativeSection> aiNarrative) {
        requireEachSectionOnce(aiNarrative);          // ← 만들기 전에 먼저 검사
        aiNarrative.forEach(n -> validateText(n.section(), n.text()));   // 고친 문장과 같은 규칙
        return new StrDraft(caseId, generationId, facts, inSectionOrder(aiNarrative));
    }

    /** 단락 순서(enum 선언 순서) 그대로 줄 세운 새 목록. AI가 보낸 순서는 믿지 않는다. */
    private static List<NarrativeSection> inSectionOrder(List<NarrativeSection> narrative) {
        return narrative.stream()
                .sorted(Comparator.comparing(NarrativeSection::section))
                .toList();
    }

    /** 섹션마다 정확히 한 번. 오류 메시지에는 개수와 섹션 이름만 넣고 본문 글자는 넣지 않는다. */
    private static void requireEachSectionOnce(List<NarrativeSection> narrative) {
        Map<StrSection, Integer> counts = new EnumMap<>(StrSection.class);
        for (NarrativeSection n : narrative) {
            counts.merge(n.section(), 1, Integer::sum);   // 섹션별로 몇 번 나왔나 센다
        }
        List<StrSection> missing = Arrays.stream(StrSection.values())
                .filter(s -> !counts.containsKey(s))       // 한 번도 안 나온 섹션
                .toList();
        List<StrSection> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)              // 두 번 이상 나온 섹션
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty() || !duplicated.isEmpty()) {
            throw new IllegalArgumentException("AI 원문 단락은 " + StrSection.values().length
                    + "개가 각각 한 번씩이어야 한다 (받은 개수 " + narrative.size()
                    + ", 빠짐 " + missing + ", 중복 " + duplicated + ")");
        }
    }
    /**
     * 본문 수정. 고친 단락(단락 이름 → 새 문장)과 수정자를 받아 새 수정본을 만든다.
     * facts는 인자에 없다 — 숫자는 못 고친다.
     */
    public StrRevision revise(Map<StrSection, String> editedTexts, Long revisedBy) {
        requireActive();
        Objects.requireNonNull(revisedBy, "revisedBy");   // 세션에서 오는 값이라 null이면 호출 쪽 버그
        validateTexts(editedTexts);

        int nextNo = currentRevision.nextNo();      // 처음 고치면 2번
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
            if (section == null) throw new InvalidSectionTextException(null, "단락 이름이 없습니다");
            validateText(section, text);
        });
    }

    private static void validateText(StrSection section, String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidSectionTextException(section, "문장이 비어 있습니다");
        }
        if (text.length() > MAX_SECTION_LENGTH) {
            throw new InvalidSectionTextException(section, MAX_SECTION_LENGTH + "자를 넘습니다");
        }
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

    /** 지금 본문: 현재 수정본의 "고친 후". 안 고쳤으면 수정본 1번(AI 원문). */
    public List<NarrativeSection> currentBody() {
        return currentRevision.getAfter();
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
