package com.team.aml.cases.domain;

import com.team.aml.user.domain.Role;
import org.junit.jupiter.api.Test;

import static com.team.aml.cases.domain.CaseAction.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** available_actions 계산(계약서 §4)과 초안 없는 종결(API-06) — DB 없이. */
class CaseActionTest {

    private static final long SYSTEM = 1L;
    private static final long INV = 7L;
    private static final long INV_B = 8L;
    private static final long ADMIN = 2L;
    private static final long APR = 9L;

    private static Case opened() {
        return Case.open("CASE-2022-000057", 142L, "010_A001", null, SYSTEM);
    }

    private static Case reviewing() {
        Case c = opened();
        c.assign(INV, INV);
        return c;
    }

    private static Case escalated() {
        Case c = reviewing();
        c.escalate(INV, "STR 필요");
        return c;
    }

    private static Case drafted() {
        Case c = escalated();
        c.attachDraft(300L, INV);
        return c;
    }

    private static Case rejected(RejectionReasonCode code) {
        Case c = drafted();
        c.submitForApproval(INV);
        c.reject(APR, code, "사유");
        return c;
    }

    @Test
    void OPEN은_조사역과_관리자에게_배정만_보인다() {
        assertThat(CaseAction.availableFor(opened(), INV, Role.INVESTIGATOR)).containsExactly(ASSIGN);
        assertThat(CaseAction.availableFor(opened(), ADMIN, Role.ADMIN)).containsExactly(ASSIGN);
        assertThat(CaseAction.availableFor(opened(), APR, Role.APPROVER)).isEmpty();
    }

    @Test
    void REVIEWING_담당은_메모_요약_에스컬레이션_종료() {
        assertThat(CaseAction.availableFor(reviewing(), INV, Role.INVESTIGATOR))
                .containsExactly(UPDATE_NOTES, REFRESH_SUMMARY, ESCALATE, CLOSE);
    }

    @Test
    void 담당이_아닌_조사역과_승인자는_아무_버튼도_없고_관리자는_재배정만() {
        assertThat(CaseAction.availableFor(reviewing(), INV_B, Role.INVESTIGATOR)).isEmpty();
        assertThat(CaseAction.availableFor(reviewing(), APR, Role.APPROVER)).isEmpty();
        assertThat(CaseAction.availableFor(reviewing(), ADMIN, Role.ADMIN)).containsExactly(REASSIGN);
    }

    @Test
    void ESCALATED는_초안_생성과_초안_없는_종료() {
        assertThat(CaseAction.availableFor(escalated(), INV, Role.INVESTIGATOR))
                .containsExactly(UPDATE_NOTES, REFRESH_SUMMARY, CLOSE, GENERATE_DRAFT);
    }

    @Test
    void 초안이_있으면_종료는_CLOSE_WITH_DRAFT() {
        assertThat(CaseAction.availableFor(drafted(), INV, Role.INVESTIGATOR))
                .containsExactly(UPDATE_NOTES, REFRESH_SUMMARY, CLOSE_WITH_DRAFT, GENERATE_DRAFT);
    }

    @Test
    void 반려는_코드에_따라_갈린다_보완은_편집_재생성_보고안함은_종료만() {
        assertThat(CaseAction.availableFor(rejected(RejectionReasonCode.NEED_REVISION), INV, Role.INVESTIGATOR))
                .containsExactly(UPDATE_NOTES, REFRESH_SUMMARY, GENERATE_DRAFT);
        assertThat(CaseAction.availableFor(rejected(RejectionReasonCode.NOT_REPORTABLE), INV, Role.INVESTIGATOR))
                .containsExactly(CLOSE_WITH_DRAFT);
    }

    @Test
    void 승인_대기와_끝_상태는_담당에게도_버튼이_없다() {
        Case pending = drafted();
        pending.submitForApproval(INV);
        assertThat(CaseAction.availableFor(pending, INV, Role.INVESTIGATOR)).isEmpty();

        Case closed = reviewing();
        closed.close(INV, CloseReason.NO_ACTION, "조치 없음");
        assertThat(CaseAction.availableFor(closed, INV, Role.INVESTIGATOR)).isEmpty();
        assertThat(CaseAction.availableFor(closed, ADMIN, Role.ADMIN)).isEmpty();
    }

    // ── closeWithoutDraft ─────────────────────────────────────

    @Test
    void 초안_없는_종결은_ESCALATED에서도_된다() {
        Case c = escalated();
        c.closeWithoutDraft(INV, CloseReason.FALSE_POSITIVE, "오탐");
        assertThat(c.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(c.getCloseReason()).isEqualTo(CloseReason.FALSE_POSITIVE);
    }

    @Test
    void 초안이_있으면_CLOSE_REQUIRES_STR이고_상태는_그대로() {
        Case c = drafted();
        assertThatThrownBy(() -> c.closeWithoutDraft(INV, CloseReason.NO_ACTION, "종료"))
                .isInstanceOf(CloseRequiresStrException.class);
        assertThat(c.getStatus()).isEqualTo(CaseStatus.DRAFTED);
    }

    @Test
    void 닫을_수_없는_상태면_초안이_있어도_상태_오류가_먼저다() {
        Case c = drafted();
        c.submitForApproval(INV);
        assertThatThrownBy(() -> c.closeWithoutDraft(INV, CloseReason.NO_ACTION, "종료"))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }
}
