package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.user.domain.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StrDraft 도메인 단위 테스트 (스프링 없이 돈다).
 * 기준: 채영 str 작업 가이드 W2 "먼저 쓸 테스트", 레퍼런스 6.1 ①, 10/27 규약 확정 회의.
 */
class StrDraftTest {

    private static final Long CASE_ID = 10L;
    private static final Long INVESTIGATOR = 7L;
    private static final String GENERATION_ID = "gen-1";

    /** 사실값(facts). 부를 때마다 새 객체라 "원래 값"과 비교할 수 있다. */
    private static JsonNode facts() {
        return new ObjectMapper().createObjectNode()
                .put("total_amount", 98_000_000)
                .put("tx_count", 12);
    }

    /**
     * AI 원문. 단락(StrSection) 전부에 문장과 근거 거래 번호를 붙인다.
     * 근거 번호는 transaction_id(bigint, 10/27 회의 1번)라 Long이다.
     */
    private static List<NarrativeSection> aiNarrative() {
        return StrSection.V1.stream()
                .map(s -> new NarrativeSection(
                        s,
                        "AI " + s + " 문장",
                        List.of(5_120_000L + s.ordinal(), 5_121_000L + s.ordinal())))
                .toList();
    }

    private static StrDraft newDraft() {
        return StrDraft.create(CASE_ID, GENERATION_ID, facts(), aiNarrative());
    }

    private static NarrativeSection section(List<NarrativeSection> body, StrSection s) {
        return body.stream().filter(n -> n.section() == s).findFirst().orElseThrow();
    }

    // 1
    @Test
    void 새_초안은_ACTIVE다() {
        StrDraft draft = newDraft();

        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.ACTIVE);
        assertThat(draft.getCaseId()).isEqualTo(CASE_ID);
        assertThat(draft.getGenerationId()).isEqualTo(GENERATION_ID);
        assertThat(draft.currentBody()).isEqualTo(aiNarrative());   // 안 고쳤으면 현재 본문 = AI 원문
    }

    // 1-추가: 수정본 1번 = AI 원문
    @Test
    void 새_초안은_수정본_1번으로_AI_원문을_가진다() {
        StrDraft draft = newDraft();

        StrRevision original = draft.getCurrentRevision();

        assertThat(original.getRevisionNo()).isEqualTo(1);
        assertThat(original.isAiOriginal()).isTrue();
        assertThat(original.getAfter()).isEqualTo(aiNarrative());   // 1번의 "고친 후" = AI 원문
        assertThat(original.getBefore()).isEmpty();                 // 그 앞은 없다
        assertThat(original.getEditedSections()).isEmpty();         // 사람이 고친 단락 없음
        assertThat(original.getRevisedBy()).isEqualTo(User.SYSTEM_ID); // 작성자 SYSTEM(1)
    }

    // 2
    @Test
    void revise하면_수정본_2번이_생기고_한번_더_하면_3번이_생긴다() {
        StrDraft draft = newDraft();

        StrRevision first = draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);
        StrRevision second = draft.revise(Map.of(StrSection.WHO, "고친 WHO"), INVESTIGATOR);

        assertThat(first.getRevisionNo()).isEqualTo(2);
        assertThat(second.getRevisionNo()).isEqualTo(3);
        assertThat(draft.getCurrentRevision()).isSameAs(second);
        assertThat(second.getRevisedBy()).isEqualTo(INVESTIGATOR);
        assertThat(second.getEditedSections()).containsExactly(StrSection.WHO);
    }

    // 3
    @Test
    void 세번째_수정본의_고치기_전은_두번째_수정본의_고친_후다() {
        StrDraft draft = newDraft();

        StrRevision first = draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);
        StrRevision second = draft.revise(Map.of(StrSection.WHO, "고친 WHO"), INVESTIGATOR);

        assertThat(first.getBefore()).isEqualTo(aiNarrative());           // 2번의 before = 1번의 after = AI 원문
        assertThat(second.getBefore()).isEqualTo(first.getAfter());       // 3번의 before = 2번의 after
        assertThat(section(second.getAfter(), StrSection.HOW).text()).isEqualTo("고친 HOW"); // 앞 수정이 유지됨
    }

    // 4
    @Test
    void revise는_고친_단락의_문장만_바꾸고_근거_번호와_다른_단락은_그대로다() {
        StrDraft draft = newDraft();

        StrRevision rev = draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);

        NarrativeSection beforeHow = section(rev.getBefore(), StrSection.HOW);
        NarrativeSection afterHow = section(rev.getAfter(), StrSection.HOW);
        assertThat(afterHow.text()).isEqualTo("고친 HOW");
        assertThat(afterHow.evidenceIds()).isEqualTo(beforeHow.evidenceIds());

        for (StrSection s : StrSection.V1) {
            if (s == StrSection.HOW) continue;
            assertThat(section(rev.getAfter(), s)).isEqualTo(section(rev.getBefore(), s));
        }
    }

    // 4-추가: 여러 단락을 한 번에 고치면 고친 단락 목록은 단락 순서(enum 순서)로 남는다
    @Test
    void 여러_단락을_고치면_고친_단락_목록은_단락_순서로_남는다() {
        StrDraft draft = newDraft();

        StrRevision rev = draft.revise(
                Map.of(StrSection.WHO, "고친 WHO", StrSection.HOW, "고친 HOW"), INVESTIGATOR);

        assertThat(rev.getEditedSections())
                .containsExactlyInAnyOrder(StrSection.WHO, StrSection.HOW)
                .isSortedAccordingTo(Comparator.comparingInt(Enum::ordinal));
    }

    // 5
    @Test
    void 빈_단락과_5000자를_넘는_단락은_거부한다() {
        StrDraft draft = newDraft();

        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, ""), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "   "), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "가".repeat(5_001)), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);

        // 경계: 정확히 5,000자는 통과. 앞의 거부된 시도는 수정본 번호를 쓰지 않았다.
        StrRevision ok = draft.revise(Map.of(StrSection.HOW, "가".repeat(5_000)), INVESTIGATOR);
        assertThat(ok.getRevisionNo()).isEqualTo(2);
    }

    // 6
    @Test
    void lock_뒤_revise하면_DraftLockedException() {
        StrDraft draft = newDraft();

        draft.lock();

        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.LOCKED);
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR))
                .isInstanceOf(DraftLockedException.class);
    }

    // 7
    @Test
    void supersede_뒤_상태는_SUPERSEDED이고_revise도_거부한다() {
        StrDraft draft = newDraft();

        draft.supersede();

        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.SUPERSEDED);
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR))
                .isInstanceOf(DraftLockedException.class);   // 가이드: ACTIVE가 아니면 DraftLockedException
    }

    // 8
    @Test
    void facts는_revise_뒤에도_그대로이고_revise에는_facts_인자가_없다() {
        StrDraft draft = newDraft();

        draft.revise(Map.of(StrSection.SUMMARY, "고친 SUMMARY"), INVESTIGATOR);

        assertThat(draft.getFacts()).isEqualTo(facts());

        Method[] revises = Arrays.stream(StrDraft.class.getMethods())
                .filter(m -> m.getName().equals("revise"))
                .toArray(Method[]::new);
        assertThat(revises).isNotEmpty();
        for (Method m : revises) {
            assertThat(m.getParameterTypes()).doesNotContain(JsonNode.class, ObjectNode.class);
        }
    }

    // 8-추가: 밖에서 받은 facts를 바꿔도 초안의 facts는 바뀌지 않는다 (숫자는 아무도 못 고친다)
    @Test
    void getFacts로_받은_값을_바꿔도_초안의_facts는_그대로다() {
        StrDraft draft = newDraft();

        JsonNode taken = draft.getFacts();
        ((ObjectNode) taken).put("total_amount", 1);

        assertThat(draft.getFacts()).isEqualTo(facts());
    }

    // 9: 금지 전이 — 잠긴(승인된) 초안은 대체할 수 없다
    @Test
    void 잠긴_초안은_대체할_수_없다() {
        StrDraft draft = newDraft();
        draft.lock();

        assertThatThrownBy(draft::supersede)
                .isInstanceOf(DraftLockedException.class);
        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.LOCKED);   // 상태도 그대로
    }

    // 10: 금지 전이 — 대체된 초안은 잠글 수 없다
    @Test
    void 대체된_초안은_잠글_수_없다() {
        StrDraft draft = newDraft();
        draft.supersede();

        assertThatThrownBy(draft::lock)
                .isInstanceOf(DraftLockedException.class);
        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.SUPERSEDED);
    }

    // 11: AI 원문 단락이 섹션마다 한 번씩이 아니면 초안을 만들지 않는다
    @Test
    void 단락이_섹션마다_한_번씩이_아니면_초안을_만들지_않는다() {
        List<NarrativeSection> missing = aiNarrative().subList(1, StrSection.V1.size());

        List<NarrativeSection> duplicated = new ArrayList<>(aiNarrative());
        duplicated.add(new NarrativeSection(StrSection.WHO, "비밀 문장", List.of(1L)));       // WHO 두 번

        List<NarrativeSection> swapped = new ArrayList<>(aiNarrative());
        swapped.set(0, new NarrativeSection(StrSection.WHO, "비밀 문장", List.of(1L)));       // 개수는 맞는데 SUMMARY 대신 WHO

        for (List<NarrativeSection> bad : List.of(missing, duplicated, swapped)) {
            assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .doesNotContain("문장");   // 오류 메시지에 본문 글자를 넣지 않는다
        }
        assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), missing))
                .hasMessageContaining("SUMMARY");
        assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), duplicated))
                .hasMessageContaining("WHO");
    }

    // 12: AI가 섹션 순서를 섞어 보내도 본문은 StrSection 순서로 저장된다
    @Test
    void AI_원문_단락_순서가_섞여_와도_섹션_순서로_저장한다() {
        List<NarrativeSection> reversed = new ArrayList<>(aiNarrative());
        Collections.reverse(reversed);                                          // CONCLUSION, …, SUMMARY

        StrDraft draft = StrDraft.create(CASE_ID, GENERATION_ID, facts(), reversed);

        assertThat(draft.currentBody()).isEqualTo(aiNarrative());              // 순서까지 같다
        StrRevision rev = draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR);
        assertThat(rev.getAfter())
                .extracting(NarrativeSection::section)
                .containsExactlyElementsOf(StrSection.V1);                         // 고친 뒤에도 그대로
    }

    // 14: 단락 이름이 null이면 거부, 수정자가 null이면 거부. 거부된 시도는 수정본 번호를 쓰지 않는다
    @Test
    void 단락_이름이나_수정자가_null이면_revise를_거부한다() {
        StrDraft draft = newDraft();
        Map<StrSection, String> nullSection = new HashMap<>();
        nullSection.put(null, "고친 문장");

        assertThatThrownBy(() -> draft.revise(nullSection, INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "고친 HOW"), null))
                .isInstanceOf(NullPointerException.class);

        assertThat(draft.getCurrentRevision().getRevisionNo()).isEqualTo(1);
    }

    // 13: AI 원문도 고친 문장과 같은 규칙 — 비었거나 5,000자를 넘으면 초안을 만들지 않는다
    @Test
    void AI_원문_단락이_비었거나_5000자를_넘으면_초안을_만들지_않는다() {
        for (String bad : List.of("", "   ", "가".repeat(5_001))) {
            List<NarrativeSection> narrative = aiNarrative().stream()
                    .map(n -> n.section() == StrSection.HOW
                            ? new NarrativeSection(n.section(), bad, n.evidenceIds())
                            : n)
                    .toList();

            assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), narrative))
                    .isInstanceOf(InvalidSectionTextException.class)
                    .hasMessageContaining("HOW");
        }
    }

    // 15: generationId가 비면 초안을 만들지 않는다 — null 초안과 null 검증이 "같은 초안"으로 통과하지 않게
    @Test
    void generationId가_비었으면_초안을_만들지_않는다() {
        for (String bad : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> StrDraft.create(CASE_ID, bad, facts(), aiNarrative()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("generationId");
        }
    }

    // 16: 글자 수는 NFC로 맞춘 뒤 UTF-16 길이로 센다 (11/1 공지, AI 06_verification과 같은 기준)
    @Test
    void 글자_수는_NFC로_맞춘_뒤_UTF16_길이로_센다() {
        StrDraft draft = newDraft();
        String nfd5000 = Normalizer.normalize("가".repeat(5_000), Normalizer.Form.NFD);   // 자소로 나뉘어 length() 10,000

        assertThat(draft.revise(Map.of(StrSection.HOW, "가".repeat(5_000)), INVESTIGATOR).getRevisionNo())
                .isEqualTo(2);                                                              // 5,000자 통과
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "가".repeat(5_001)), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);                           // 5,001자 거부
        assertThatThrownBy(() -> draft.revise(Map.of(StrSection.HOW, "가".repeat(4_999) + "😀"), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);                           // 이모지는 2로 세서 5,001
        assertThat(draft.revise(Map.of(StrSection.HOW, nfd5000), INVESTIGATOR).getRevisionNo())
                .isEqualTo(3);                                                              // NFD 한글 5,000자 통과
    }

    // 17: 끝 상태에서 같은 전이를 다시 불러도 거부하고 상태는 그대로다
    @Test
    void 잠긴_초안을_다시_잠그면_거부하고_상태는_그대로다() {
        StrDraft draft = newDraft();
        draft.lock();

        assertThatThrownBy(draft::lock).isInstanceOf(DraftLockedException.class);
        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.LOCKED);
    }

    // 18
    @Test
    void 대체된_초안을_다시_대체하면_거부하고_상태는_그대로다() {
        StrDraft draft = newDraft();
        draft.supersede();

        assertThatThrownBy(draft::supersede).isInstanceOf(DraftLockedException.class);
        assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.SUPERSEDED);
    }

    // ---- 서술부 7항목(V2) 전환 — 11/6, 서식 안건 3·6. 옛 6섹션(V1) 초안도 그대로 산다 ----

    /** 7항목 본문. 일부러 순서를 섞어 둔다(AI가 보낸 순서는 믿지 않는다). */
    private static List<NarrativeSection> v2Narrative() {
        List<NarrativeSection> body = new ArrayList<>(StrSection.V2.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of(5_130_000L + s.ordinal())))
                .toList());
        Collections.reverse(body);                                                   // OPINION, …, SUBJECT
        return body;
    }

    // V2-1
    @Test
    void 서술_7항목_초안도_만들고_본문은_7항목_순서로_저장한다() {
        StrDraft draft = StrDraft.create(CASE_ID, GENERATION_ID, facts(), v2Narrative());

        assertThat(draft.currentBody())
                .extracting(NarrativeSection::section)
                .containsExactlyElementsOf(StrSection.V2);                           // SUBJECT·DATE·PLACE·INSTRUMENT·METHOD·GROUNDS·OPINION
        StrRevision rev = draft.revise(Map.of(StrSection.GROUNDS, "고친 GROUNDS"), INVESTIGATOR);
        assertThat(rev.getRevisionNo()).isEqualTo(2);
        assertThat(section(rev.getAfter(), StrSection.GROUNDS).text()).isEqualTo("고친 GROUNDS");
    }

    // V2-2
    @Test
    void 섹션_6개_세트와_7개_세트가_섞이면_초안을_만들지_않는다() {
        List<NarrativeSection> mixed = new ArrayList<>(aiNarrative());               // V1 6개 …
        mixed.set(5, new NarrativeSection(StrSection.OPINION, "비밀 문장", List.of())); // … CONCLUSION 대신 OPINION
        List<NarrativeSection> both = new ArrayList<>(aiNarrative());
        both.addAll(v2Narrative());                                                  // 13개 전부

        for (List<NarrativeSection> bad : List.of(mixed, both)) {
            assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .doesNotContain("문장");                                         // 본문 글자는 메시지에 없다
        }
        assertThatThrownBy(() -> StrDraft.create(CASE_ID, GENERATION_ID, facts(), mixed))
                .hasMessageContaining("OPINION")                                     // 다른 세트에서 온 것
                .hasMessageContaining("CONCLUSION");                                 // 빠진 것
    }

    // V2-3
    @Test
    void 초안에_없는_세트의_단락으로_고치면_거부하고_수정본_번호를_쓰지_않는다() {
        StrDraft v1 = newDraft();
        StrDraft v2 = StrDraft.create(CASE_ID, GENERATION_ID, facts(), v2Narrative());

        assertThatThrownBy(() -> v1.revise(Map.of(StrSection.SUBJECT, "고친 SUBJECT"), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);                    // 조용히 버리지 않는다
        assertThatThrownBy(() -> v2.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR))
                .isInstanceOf(InvalidSectionTextException.class);
        assertThat(v1.getCurrentRevision().getRevisionNo()).isEqualTo(1);
        assertThat(v2.getCurrentRevision().getRevisionNo()).isEqualTo(1);
    }

}
