package com.team.aml.cases.domain;

import com.team.aml.shared.domain.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 질문 입력 규칙(규약 5.4·계약서 5.3 A8)과 저장 규칙(V017 CHECK) — DB 없이 (SCRUM-302). */
class CaseQuestionTest {

    // ── 질문 입력 ─────────────────────────────────────────────

    @Test
    void 정상_300자와_앞뒤_공백은_그대로_받는다() {
        assertThatCode(() -> CaseQuestion.requireValidQuestion("가".repeat(300))).doesNotThrowAnyException();
        assertThatCode(() -> CaseQuestion.requireValidQuestion(" 몇 건이야? ")).doesNotThrowAnyException();
        assertThat(CaseQuestion.record(1L, "  몇 건이야?  ", CaseQuestionStatus.NO_DATA, null, null, List.of(), "g", null)
                .getQuestion()).isEqualTo("  몇 건이야?  ");   // 자르지 않는다
    }

    @Test
    void 경계_글자_수는_NFC_뒤_UTF_16으로_센다() {
        // NFD 로 쪼갠 한글 300자 = 원문 900 단위지만 NFC 로 300자 → 통과 (화면 text.normalize('NFC').length 와 같음)
        String nfd = Normalizer.normalize("한".repeat(300), Normalizer.Form.NFD);
        assertThat(nfd.length()).isEqualTo(900);
        assertThatCode(() -> CaseQuestion.requireValidQuestion(nfd)).doesNotThrowAnyException();
        // 이모지는 UTF-16 2단위 — 150개 = 300 통과, 151개 = 302 거절
        assertThatCode(() -> CaseQuestion.requireValidQuestion("😀".repeat(150))).doesNotThrowAnyException();
        assertQuestionRejected("😀".repeat(151));
        assertQuestionRejected("가".repeat(301));
        assertQuestionRejected(" " + "가".repeat(300));   // 앞뒤 공백도 센다
    }

    @ParameterizedTest(name = "[{index}] 공백만")
    @ValueSource(strings = {"", " ", "   ", "\t\n", " ", "　", "  ", "\u001c\u001f", "\u0085", " "})
    void 실패_비었거나_공백만이면_400_details_question(String blank) {   // 파이썬 str.isspace 가 참인 글자들
        assertQuestionRejected(blank);
    }

    @Test
    void 실패_없으면_400_details_question() {
        assertQuestionRejected(null);
    }

    @Test
    void 경계_공백이_아닌_보이지_않는_글자는_공백으로_보지_않는다() {   // 파이썬 isspace 도 거짓 → AI 도 받는다
        assertThatCode(() -> CaseQuestion.requireValidQuestion("​")).doesNotThrowAnyException();   // ZERO WIDTH SPACE
    }

    @Test
    void 경계_원문_코드_포인트가_1200개를_넘으면_DB에_가기_전에_400() {   // V017 CHECK 가 500 으로 새지 않게
        String combining = "a" + "́".repeat(1_200);   // NFC 로도 합쳐지지 않는 결합 문자 — NFC 길이도 커서 어차피 거절
        assertQuestionRejected(combining);
    }

    private static void assertQuestionRejected(String question) {
        assertThatThrownBy(() -> CaseQuestion.requireValidQuestion(question))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.details()).containsKey("question"));
    }

    // ── 저장 규칙 (V017 CHECK 와 같음) ────────────────────────

    @Test
    void 정상_상태별_칸이_맞으면_기록한다() {
        CaseQuestion answered = CaseQuestion.record(1L, "몇 건?", CaseQuestionStatus.ANSWERED, null, "2건이에요.",
                List.of(11L, 12L), "g-1", "req-1");
        assertThat(answered.getEvidenceTransactionIds()).containsExactly(11L, 12L);
        assertThat(answered.getRequestId()).isEqualTo("req-1");
        CaseQuestion oos = CaseQuestion.record(1L, "세탁 맞아?", CaseQuestionStatus.OUT_OF_SCOPE,
                QuestionOutOfScopeReason.JUDGMENT, null, null, "g-2", null);
        assertThat(oos.getEvidenceTransactionIds()).isEmpty();
        assertThat(oos.getOutOfScopeReason()).isEqualTo(QuestionOutOfScopeReason.JUDGMENT);
    }

    @Test
    void 실패_상태와_칸이_어긋나면_기록하지_않는다() {
        assertThatThrownBy(() -> CaseQuestion.record(1L, "q", CaseQuestionStatus.ANSWERED, null, null, List.of(), "g", null))
                .isInstanceOf(IllegalArgumentException.class);   // ANSWERED 인데 답 없음
        assertThatThrownBy(() -> CaseQuestion.record(1L, "q", CaseQuestionStatus.NO_DATA, null, "답", List.of(), "g", null))
                .isInstanceOf(IllegalArgumentException.class);   // 답은 ANSWERED 만
        assertThatThrownBy(() -> CaseQuestion.record(1L, "q", CaseQuestionStatus.OUT_OF_SCOPE, null, null, List.of(), "g", null))
                .isInstanceOf(IllegalArgumentException.class);   // OUT_OF_SCOPE 인데 사유 없음
        assertThatThrownBy(() -> CaseQuestion.record(1L, "q", CaseQuestionStatus.NO_DATA,
                QuestionOutOfScopeReason.EDIT_REQUEST, null, List.of(), "g", null))
                .isInstanceOf(IllegalArgumentException.class);   // 사유는 OUT_OF_SCOPE 만
        assertThatThrownBy(() -> CaseQuestion.record(1L, "q", CaseQuestionStatus.UNVERIFIED, null, null, List.of(5L), "g", null))
                .isInstanceOf(IllegalArgumentException.class);   // 근거 거래는 ANSWERED 만
    }
}
