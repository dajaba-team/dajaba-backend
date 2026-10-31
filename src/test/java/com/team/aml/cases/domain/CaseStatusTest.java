package com.team.aml.cases.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static com.team.aml.cases.domain.CaseStatus.*;
import static com.team.aml.cases.domain.RejectionReasonCode.NEED_REVISION;
import static com.team.aml.cases.domain.RejectionReasonCode.NOT_REPORTABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CaseStatus 전이 단위 테스트 (스프링 없이 돈다).
 * 기준: docs/06_features/cases.md 전이 표, CASES 명세 v0.6 §2.2, 10/27 회의 안건 11·24.
 * 그림의 화살표 하나 = 테스트 하나. 금지 전이·끝 상태도 각각.
 */
class CaseStatusTest {

    private static void assertAllowed(CaseStatus from, CaseStatus to) {
        assertAllowed(from, to, null);
    }

    private static void assertAllowed(CaseStatus from, CaseStatus to, RejectionReasonCode code) {
        assertThat(from.canTransitionTo(to, code)).isTrue();
        assertThatCode(() -> from.validateTransition(to, code)).doesNotThrowAnyException();
    }

    private static void assertForbidden(CaseStatus from, CaseStatus to) {
        assertForbidden(from, to, null);
    }

    private static void assertForbidden(CaseStatus from, CaseStatus to, RejectionReasonCode code) {
        assertThat(from.canTransitionTo(to, code)).isFalse();
        assertThatThrownBy(() -> from.validateTransition(to, code))
                .isInstanceOf(InvalidCaseTransitionException.class)
                .satisfies(e -> assertThat(((InvalidCaseTransitionException) e).code())
                        .isEqualTo("INVALID_CASE_TRANSITION"));
    }

    // ── 허용 12 ──────────────────────────────────────────────

    @Test
    void OPEN에서_배정하면_REVIEWING() {
        assertAllowed(OPEN, REVIEWING);
    }

    @Test
    void REVIEWING에서_상신하면_ESCALATED() {
        assertAllowed(REVIEWING, ESCALATED);
    }

    @Test
    void REVIEWING에서_사유_종결하면_CLOSED() {
        assertAllowed(REVIEWING, CLOSED);
    }

    @Test
    void ESCALATED에서_초안을_연결하면_DRAFTED() {
        assertAllowed(ESCALATED, DRAFTED);
    }

    @Test
    void ESCALATED에서_사유_종결하면_CLOSED() {
        assertAllowed(ESCALATED, CLOSED);
    }

    @Test
    void DRAFTED에서_재생성하면_DRAFTED_그대로() {
        assertAllowed(DRAFTED, DRAFTED);
    }

    @Test
    void DRAFTED에서_승인_요청하면_PENDING_APPROVAL() {
        assertAllowed(DRAFTED, PENDING_APPROVAL);
    }

    @Test
    void DRAFTED에서_초안_정리와_함께_종결하면_CLOSED() {
        assertAllowed(DRAFTED, CLOSED);
    }

    @Test
    void PENDING_APPROVAL에서_승인하면_APPROVED() {
        assertAllowed(PENDING_APPROVAL, APPROVED);
    }

    @Test
    void PENDING_APPROVAL에서_반려하면_REJECTED() {
        assertAllowed(PENDING_APPROVAL, REJECTED);
    }

    @Test
    void NEED_REVISION_반려는_보완_저장하면_DRAFTED() {
        assertAllowed(REJECTED, DRAFTED, NEED_REVISION);
    }

    @Test
    void NOT_REPORTABLE_반려는_사유_종결하면_CLOSED() {
        assertAllowed(REJECTED, CLOSED, NOT_REPORTABLE);
    }

    // ── 금지 ────────────────────────────────────────────────

    @Test
    void OPEN에서_바로_상신할_수_없다() {
        assertForbidden(OPEN, ESCALATED);
    }

    @Test
    void OPEN에서_배정_없이_종결할_수_없다() {
        assertForbidden(OPEN, CLOSED);
    }

    @Test
    void REVIEWING에서_초안을_바로_연결할_수_없다() {
        // 초안 생성은 상신을 자동 실행하지 않는다 (명세 §7.4)
        assertForbidden(REVIEWING, DRAFTED);
    }

    @Test
    void 승인_대기에서_바로_종결할_수_없다() {
        assertForbidden(PENDING_APPROVAL, CLOSED);
    }

    @Test
    void 승인_대기에서_본문을_고치러_DRAFTED로_돌아갈_수_없다() {
        assertForbidden(PENDING_APPROVAL, DRAFTED);
    }

    @Test
    void NEED_REVISION_반려는_종결할_수_없다() {
        assertForbidden(REJECTED, CLOSED, NEED_REVISION);
    }

    @Test
    void NOT_REPORTABLE_반려는_수정이나_재생성할_수_없다() {
        assertForbidden(REJECTED, DRAFTED, NOT_REPORTABLE);
    }

    @Test
    void 반려_코드가_없는_REJECTED는_어디로도_못_간다() {
        // DB CHECK 로 막히는 상태지만, 들어오면 갈 곳을 추측하지 않고 거부한다
        assertForbidden(REJECTED, DRAFTED, null);
        assertForbidden(REJECTED, CLOSED, null);
    }

    @Test
    void 반려에서_바로_승인_요청할_수_없다() {
        assertForbidden(REJECTED, PENDING_APPROVAL, NEED_REVISION);
    }

    @Test
    void 대상_상태가_null이면_거부한다() {
        assertForbidden(REVIEWING, null);
    }

    // ── 끝 상태 ──────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    void APPROVED에서는_어디로도_못_간다(CaseStatus to) {
        assertForbidden(APPROVED, to);
    }

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    void CLOSED에서는_어디로도_못_간다(CaseStatus to) {
        assertForbidden(CLOSED, to);
    }

    @Test
    void 끝_상태는_APPROVED와_CLOSED_둘뿐이다() {
        assertThat(Set.of(values()).stream().filter(CaseStatus::isTerminal))
                .containsExactlyInAnyOrder(APPROVED, CLOSED);
    }

    // ── 전체 표 ──────────────────────────────────────────────

    /** 8×8 전부 확인: 위 허용 12개 말고는 모두 거부. 새 화살표를 몰래 추가하면 여기서 깨진다. */
    @Test
    void 허용_표_밖의_전이는_모두_거부한다() {
        Set<String> allowed = Set.of(
                "OPEN>REVIEWING",
                "REVIEWING>ESCALATED", "REVIEWING>CLOSED",
                "ESCALATED>DRAFTED", "ESCALATED>CLOSED",
                "DRAFTED>DRAFTED", "DRAFTED>PENDING_APPROVAL", "DRAFTED>CLOSED",
                "PENDING_APPROVAL>APPROVED", "PENDING_APPROVAL>REJECTED",
                "REJECTED>DRAFTED", "REJECTED>CLOSED");   // REJECTED 는 반려 코드가 맞을 때만

        for (CaseStatus from : values()) {
            for (CaseStatus to : values()) {
                boolean expected = allowed.contains(from + ">" + to);
                boolean actual = from == REJECTED
                        ? from.canTransitionTo(to, NEED_REVISION) || from.canTransitionTo(to, NOT_REPORTABLE)
                        : from.canTransitionTo(to, null);
                assertThat(actual).as(from + " → " + to).isEqualTo(expected);
            }
        }
    }

    // ── 예외 내용 ────────────────────────────────────────────

    @Test
    void 예외에는_전후_상태와_반려_코드가_담긴다() {
        assertThatThrownBy(() -> REJECTED.validateTransition(CLOSED, NEED_REVISION))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> assertThat(e.details())
                        .containsEntry("from_status", REJECTED)
                        .containsEntry("to_status", CLOSED)
                        .containsEntry("rejection_reason_code", NEED_REVISION));
    }

    @Test
    void 반려_코드가_없으면_details에_넣지_않는다() {
        assertThatThrownBy(() -> OPEN.validateTransition(ESCALATED, null))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> assertThat(e.details())
                        .doesNotContainKey("rejection_reason_code"));
    }
}
