package com.team.aml.str.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.user.domain.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
        return Arrays.stream(StrSection.values())
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

        for (StrSection s : StrSection.values()) {
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

}
