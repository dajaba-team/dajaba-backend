package com.team.aml.cases.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static com.team.aml.cases.domain.CaseStatus.*;
import static com.team.aml.cases.domain.RejectionReasonCode.NEED_REVISION;
import static com.team.aml.cases.domain.RejectionReasonCode.NOT_REPORTABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Case 도메인 단위 테스트 (스프링·DB 없이 돈다).
 * 전이 표 자체는 CaseStatusTest 가 검사한다. 여기서는 메서드가 상태·담당자·초안·반려 코드·이력을
 * 함께 바꾸는지, 실패하면 아무것도 안 바뀌는지, 상태 말고 검사하는 것(사유 필수·자기 승인 금지)을 본다.
 * 기준: docs/06_features/cases.md, CASES 명세 v0.6 §2·§8.2, 10/27 회의 안건 11·24.
 */
class CaseTest {

    private static final String CASE_NO = "CASE-2022-000057";
    private static final Long ALERT_ID = 142L;
    private static final String ACCOUNT_KEY = "010_A001";
    private static final Long SYSTEM = 1L;
    private static final Long INVESTIGATOR = 7L;
    private static final Long INVESTIGATOR_B = 8L;
    private static final Long ADMIN = 2L;
    private static final Long APPROVER = 9L;
    private static final Long DRAFT_ID = 300L;
    private static final Long NEW_DRAFT_ID = 301L;

    // ── 상태별 준비: 허용된 길로만 간다 ─────────────────────────

    private static Case opened() {
        return Case.open(CASE_NO, ALERT_ID, ACCOUNT_KEY, null, SYSTEM);
    }

    private static Case reviewing() {
        Case c = opened();
        c.assign(INVESTIGATOR, INVESTIGATOR);
        return c;
    }

    private static Case escalated() {
        Case c = reviewing();
        c.escalate(INVESTIGATOR, "STR 초안 작성 필요");
        return c;
    }

    private static Case drafted() {
        Case c = escalated();
        c.attachDraft(DRAFT_ID, INVESTIGATOR);
        return c;
    }

    private static Case pending() {
        Case c = drafted();
        c.submitForApproval(INVESTIGATOR);
        return c;
    }

    private static Case rejected(RejectionReasonCode code) {
        Case c = pending();
        c.reject(APPROVER, false, code, "거래 목적 확인 내용을 보완해 주세요.");
        return c;
    }

    private static CaseHistory lastHistory(Case c) {
        List<CaseHistory> h = c.getPendingHistories();
        return h.get(h.size() - 1);
    }

    // ── 생성 ────────────────────────────────────────────────

    @Test
    void 새_케이스는_OPEN이고_담당자_초안_반려코드가_없다() {
        Case c = opened();

        assertThat(c.getCaseNo()).isEqualTo(CASE_NO);
        assertThat(c.getAlertId()).isEqualTo(ALERT_ID);
        assertThat(c.getStatus()).isEqualTo(OPEN);
        assertThat(c.getAssigneeId()).isNull();
        assertThat(c.getCurrentDraftId()).isNull();
        assertThat(c.getRejectionReasonCode()).isNull();
        assertThat(c.getCloseReason()).isNull();
    }

    @Test
    void 생성하면_OPEN_이력이_한_행_남는다() {
        Case c = opened();

        assertThat(c.getPendingHistories()).hasSize(1);
        CaseHistory h = c.getPendingHistories().get(0);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.OPEN);
        assertThat(h.getFromStatus()).isNull();
        assertThat(h.getToStatus()).isEqualTo(OPEN);
        assertThat(h.getCreatedBy()).isEqualTo(SYSTEM);
        assertThat(h.getCaseVersionBefore()).isZero();
    }

    // ── 배정 ────────────────────────────────────────────────

    @Test
    void 배정하면_REVIEWING이_되고_담당자와_ASSIGN_이력이_생긴다() {
        Case c = opened();

        c.assign(INVESTIGATOR, ADMIN);

        assertThat(c.getStatus()).isEqualTo(REVIEWING);
        assertThat(c.getAssigneeId()).isEqualTo(INVESTIGATOR);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.ASSIGN);
        assertThat(h.getFromStatus()).isEqualTo(OPEN);
        assertThat(h.getToStatus()).isEqualTo(REVIEWING);
        assertThat(h.getCreatedBy()).isEqualTo(ADMIN);
        assertThat(h.getFromAssigneeId()).isNull();                 // V010: 배정은 받은 사람만
        assertThat(h.getToAssigneeId()).isEqualTo(INVESTIGATOR);
    }

    @Test
    void 이미_배정된_케이스는_다시_assign할_수_없다_재배정은_reassign() {
        Case c = reviewing();
        int before = c.getPendingHistories().size();

        assertThatThrownBy(() -> c.assign(INVESTIGATOR_B, INVESTIGATOR_B))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(c.getAssigneeId()).isEqualTo(INVESTIGATOR);
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    // ── 재배정 ──────────────────────────────────────────────

    @Test
    void 재배정은_상태를_그대로_두고_담당자만_바꾼다() {
        Case c = drafted();

        boolean changed = c.reassign(INVESTIGATOR_B, ADMIN, "조사 업무 분담");

        assertThat(changed).isTrue();
        assertThat(c.getStatus()).isEqualTo(DRAFTED);
        assertThat(c.getAssigneeId()).isEqualTo(INVESTIGATOR_B);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.REASSIGN);
        assertThat(h.getFromStatus()).isEqualTo(DRAFTED);
        assertThat(h.getToStatus()).isEqualTo(DRAFTED);
        assertThat(h.getComment()).isEqualTo("조사 업무 분담");
        assertThat(h.getFromAssigneeId()).isEqualTo(INVESTIGATOR);   // V010: 넘긴 사람·받은 사람
        assertThat(h.getToAssigneeId()).isEqualTo(INVESTIGATOR_B);
    }

    @Test
    void 배정_재배정이_아닌_이력에는_담당자_칸이_비어_있다() {
        Case c = pending();

        assertThat(c.getPendingHistories())
                .filteredOn(h -> h.getAction() != CaseHistoryAction.ASSIGN)
                .allSatisfy(h -> {
                    assertThat(h.getFromAssigneeId()).isNull();
                    assertThat(h.getToAssigneeId()).isNull();
                });
    }

    @Test
    void 같은_담당자로_재배정하면_아무것도_안_바뀐다() {
        Case c = reviewing();
        int before = c.getPendingHistories().size();

        boolean changed = c.reassign(INVESTIGATOR, ADMIN, null);

        assertThat(changed).isFalse();
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    @Test
    void 재배정해도_반려_코드는_그대로다() {
        Case c = rejected(NEED_REVISION);

        c.reassign(INVESTIGATOR_B, ADMIN, null);

        assertThat(c.getStatus()).isEqualTo(REJECTED);
        assertThat(c.getRejectionReasonCode()).isEqualTo(NEED_REVISION);
    }

    @Test
    void OPEN_승인대기_끝_상태에서는_재배정할_수_없다() {
        for (Case c : List.of(opened(), pending())) {
            assertThatThrownBy(() -> c.reassign(INVESTIGATOR_B, ADMIN, null))
                    .isInstanceOf(InvalidCaseTransitionException.class);
        }
        Case approved = pending();
        approved.approve(APPROVER, false);
        assertThatThrownBy(() -> approved.reassign(INVESTIGATOR_B, ADMIN, null))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 상신 ────────────────────────────────────────────────

    @Test
    void 상신하면_ESCALATED가_되고_사유가_이력에_남는다() {
        Case c = reviewing();

        c.escalate(INVESTIGATOR, "STR 초안 작성 및 거래 목적 추가 확인 필요");

        assertThat(c.getStatus()).isEqualTo(ESCALATED);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.ESCALATE);
        assertThat(h.getComment()).isEqualTo("STR 초안 작성 및 거래 목적 추가 확인 필요");
    }

    @Test
    void 상신_사유가_비어_있으면_거부하고_상태는_그대로다() {
        for (String blank : new String[]{null, "", "   "}) {
            Case c = reviewing();
            int before = c.getPendingHistories().size();

            assertThatThrownBy(() -> c.escalate(INVESTIGATOR, blank))
                    .isInstanceOf(CommentRequiredException.class)
                    .satisfies(e -> assertThat(((CommentRequiredException) e).code())
                            .isEqualTo("COMMENT_REQUIRED"));

            assertThat(c.getStatus()).isEqualTo(REVIEWING);
            assertThat(c.getPendingHistories()).hasSize(before);
        }
    }

    @Test
    void OPEN에서_상신하면_상태_오류가_먼저다() {
        // 사유가 있어도 OPEN → ESCALATED 는 금지
        assertThatThrownBy(() -> opened().escalate(INVESTIGATOR, "사유"))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 초안 연결·재생성 ─────────────────────────────────────

    @Test
    void 초안을_연결하면_DRAFTED가_되고_현재_초안이_정해진다() {
        Case c = escalated();

        c.attachDraft(DRAFT_ID, INVESTIGATOR);

        assertThat(c.getStatus()).isEqualTo(DRAFTED);
        assertThat(c.getCurrentDraftId()).isEqualTo(DRAFT_ID);
        assertThat(lastHistory(c).getAction()).isEqualTo(CaseHistoryAction.ATTACH_DRAFT);
    }

    @Test
    void 재생성하면_DRAFTED_그대로_현재_초안만_바뀐다() {
        Case c = drafted();

        c.attachDraft(NEW_DRAFT_ID, INVESTIGATOR);

        assertThat(c.getStatus()).isEqualTo(DRAFTED);
        assertThat(c.getCurrentDraftId()).isEqualTo(NEW_DRAFT_ID);
        assertThat(lastHistory(c).getFromStatus()).isEqualTo(DRAFTED);
    }

    @Test
    void 상신_전에는_초안을_연결할_수_없다() {
        Case c = reviewing();

        assertThatThrownBy(() -> c.attachDraft(DRAFT_ID, INVESTIGATOR))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getCurrentDraftId()).isNull();
    }

    // ── 승인 요청·승인 ───────────────────────────────────────

    @Test
    void 승인_요청하면_PENDING_APPROVAL() {
        Case c = drafted();

        c.submitForApproval(INVESTIGATOR);

        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
        assertThat(lastHistory(c).getAction()).isEqualTo(CaseHistoryAction.SUBMIT_FOR_APPROVAL);
    }

    @Test
    void 다른_사람이_승인하면_APPROVED() {
        Case c = pending();

        c.approve(APPROVER, false);

        assertThat(c.getStatus()).isEqualTo(APPROVED);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.APPROVE);
        assertThat(h.getCreatedBy()).isEqualTo(APPROVER);
    }

    @Test
    void 담당자는_자기_케이스를_승인할_수_없다() {
        Case c = pending();
        int before = c.getPendingHistories().size();

        assertThatThrownBy(() -> c.approve(INVESTIGATOR, false))
                .isInstanceOf(SelfApprovalException.class)
                .satisfies(e -> assertThat(((SelfApprovalException) e).code())
                        .isEqualTo("SELF_APPROVAL"));

        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    @Test
    void 예전에_담당했던_사람은_승인할_수_없다() {
        Case c = pending();
        int before = c.getPendingHistories().size();

        // 지금 담당은 INVESTIGATOR — APPROVER 는 예전 담당이었다고 application 이 알려 준 경우 (미결 32)
        assertThatThrownBy(() -> c.approve(APPROVER, true))
                .isInstanceOf(SelfApprovalException.class);

        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    @Test
    void 지금_담당자는_예전_담당_표시가_없어도_승인할_수_없다() {
        assertThatThrownBy(() -> pending().approve(INVESTIGATOR, false))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    void 지금_담당자와_예전_담당자는_반려도_할_수_없다() {
        for (var attempt : List.<Consumer<Case>>of(
                c -> c.reject(INVESTIGATOR, false, NEED_REVISION, "보완"),       // 지금 담당
                c -> c.reject(APPROVER, true, NOT_REPORTABLE, "보고 안 함"))) {  // 예전 담당
            Case c = pending();
            int before = c.getPendingHistories().size();

            assertThatThrownBy(() -> attempt.accept(c))
                    .isInstanceOf(SelfApprovalException.class)
                    .satisfies(e -> assertThat(((SelfApprovalException) e).code()).isEqualTo("SELF_APPROVAL"));

            assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
            assertThat(c.getRejectionReasonCode()).isNull();
            assertThat(c.getPendingHistories()).hasSize(before);
        }
    }

    @Test
    void 승인_대기가_아니면_자기승인_검사보다_상태_오류가_먼저다() {
        assertThatThrownBy(() -> drafted().approve(INVESTIGATOR, false))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 반려 ────────────────────────────────────────────────

    @Test
    void 반려하면_REJECTED와_반려_코드가_함께_저장된다() {
        Case c = pending();

        c.reject(APPROVER, false, NEED_REVISION, "거래 목적 확인 내용을 보완해 주세요.");

        assertThat(c.getStatus()).isEqualTo(REJECTED);
        assertThat(c.getRejectionReasonCode()).isEqualTo(NEED_REVISION);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.REJECT);
        assertThat(h.getReasonCode()).isEqualTo("NEED_REVISION");
        assertThat(h.getComment()).isEqualTo("거래 목적 확인 내용을 보완해 주세요.");
    }

    @Test
    void 반려_사유가_비어_있으면_거부한다() {
        Case c = pending();

        assertThatThrownBy(() -> c.reject(APPROVER, false, NOT_REPORTABLE, " "))
                .isInstanceOf(CommentRequiredException.class);

        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
        assertThat(c.getRejectionReasonCode()).isNull();
    }

    // ── 반려 뒤: NEED_REVISION ───────────────────────────────

    @Test
    void 보완_반려는_같은_초안을_고쳐_DRAFTED로_돌아가고_반려코드가_지워진다() {
        Case c = rejected(NEED_REVISION);

        c.reopenDraft(INVESTIGATOR);

        assertThat(c.getStatus()).isEqualTo(DRAFTED);
        assertThat(c.getCurrentDraftId()).isEqualTo(DRAFT_ID);
        assertThat(c.getRejectionReasonCode()).isNull();
        assertThat(lastHistory(c).getAction()).isEqualTo(CaseHistoryAction.REOPEN_DRAFT);
    }

    @Test
    void 보완_반려가_아니면_reopenDraft는_409이고_이력이_남지_않는다() {
        Case e = escalated();
        int before = e.getPendingHistories().size();

        assertThatThrownBy(() -> e.reopenDraft(INVESTIGATOR))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(e.getStatus()).isEqualTo(ESCALATED);
        assertThat(e.getPendingHistories()).hasSize(before);

        Case d = drafted();
        assertThatThrownBy(() -> d.reopenDraft(INVESTIGATOR))
                .isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 승인 요청·승인·반려 대상 초안 (SubmitCaseForApproval·ApproveCase·RejectCase) ──────

    @Test
    void 현재_초안이면_통과하고_다른_초안이거나_초안이_없으면_409에_현재_초안이_실린다() {
        pending().requireCurrentDraft(DRAFT_ID, CaseHistoryAction.APPROVE);

        assertThatThrownBy(() -> pending().requireCurrentDraft(NEW_DRAFT_ID, CaseHistoryAction.APPROVE))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> assertThat(e.details())
                        .containsEntry("action", CaseHistoryAction.APPROVE)
                        .containsEntry("draft_id", NEW_DRAFT_ID)
                        .containsEntry("current_draft_id", DRAFT_ID));
        assertThatThrownBy(() -> escalated().requireCurrentDraft(DRAFT_ID, CaseHistoryAction.SUBMIT_FOR_APPROVAL))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                        e -> assertThat(e.details()).doesNotContainKey("current_draft_id"));
    }

    // ── 수정본 저장 가능 여부 (TouchCaseForRevision, #43 A안) ──────

    @Test
    void 수정본은_DRAFTED와_보완_반려에서_현재_초안일_때만_저장할_수_있다() {
        drafted().requireRevisable(DRAFT_ID);
        Case r = rejected(NEED_REVISION);
        r.requireRevisable(DRAFT_ID);

        assertThat(r.isRejectedForRevision()).isTrue();
        assertThat(drafted().isRejectedForRevision()).isFalse();
        assertThat(r.getStatus()).isEqualTo(REJECTED);   // 확인만 하고 바꾸지 않는다
    }

    @Test
    void 보고_대상_아님_반려_승인_대기_다른_초안이면_수정본_저장은_409_REVISE_DRAFT() {
        for (Case c : List.of(rejected(NOT_REPORTABLE), pending(), escalated())) {
            assertThatThrownBy(() -> c.requireRevisable(DRAFT_ID))
                    .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                            e -> assertThat(e.details()).containsEntry("action", "REVISE_DRAFT"));
        }
        assertThatThrownBy(() -> rejected(NEED_REVISION).requireRevisable(NEW_DRAFT_ID))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class,
                        e -> assertThat(e.details()).containsEntry("current_draft_id", DRAFT_ID));
    }

    @Test
    void 보완_반려는_새_초안으로_재생성해도_DRAFTED가_되고_반려코드가_지워진다() {
        Case c = rejected(NEED_REVISION);

        c.attachDraft(NEW_DRAFT_ID, INVESTIGATOR);

        assertThat(c.getStatus()).isEqualTo(DRAFTED);
        assertThat(c.getCurrentDraftId()).isEqualTo(NEW_DRAFT_ID);
        assertThat(c.getRejectionReasonCode()).isNull();
    }

    @Test
    void 보완_반려는_종결할_수_없고_반려코드가_남는다() {
        Case c = rejected(NEED_REVISION);

        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, "종결"))
                .isInstanceOf(InvalidCaseTransitionException.class);

        assertThat(c.getStatus()).isEqualTo(REJECTED);
        assertThat(c.getRejectionReasonCode()).isEqualTo(NEED_REVISION);
    }

    // ── 반려 뒤: NOT_REPORTABLE ──────────────────────────────

    @Test
    void 보고대상아님_반려는_사유를_남겨_종결하고_과거_반려코드는_이력에_남는다() {
        Case c = rejected(NOT_REPORTABLE);

        c.close(INVESTIGATOR, CloseReason.NO_ACTION, "승인자 판단에 따라 보고 대상 아님으로 종결");

        assertThat(c.getStatus()).isEqualTo(CLOSED);
        assertThat(c.getRejectionReasonCode()).isNull();
        assertThat(c.getPendingHistories())
                .anySatisfy(h -> {
                    assertThat(h.getAction()).isEqualTo(CaseHistoryAction.REJECT);
                    assertThat(h.getReasonCode()).isEqualTo("NOT_REPORTABLE");
                });
    }

    @Test
    void 보고대상아님_반려는_수정이나_재생성할_수_없다() {
        Case c = rejected(NOT_REPORTABLE);

        assertThatThrownBy(() -> c.reopenDraft(INVESTIGATOR))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> c.attachDraft(NEW_DRAFT_ID, INVESTIGATOR))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getCurrentDraftId()).isEqualTo(DRAFT_ID);
    }

    // ── 종결 ────────────────────────────────────────────────

    @Test
    void 조사_중_종결하면_CLOSED와_종결사유가_저장된다() {
        Case c = reviewing();

        c.close(INVESTIGATOR, CloseReason.FALSE_POSITIVE, "확인한 근거 범위에서 정상 거래로 판단");

        assertThat(c.getStatus()).isEqualTo(CLOSED);
        assertThat(c.getCloseReason()).isEqualTo(CloseReason.FALSE_POSITIVE);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.CLOSE);
        assertThat(h.getReasonCode()).isEqualTo("FALSE_POSITIVE");
    }

    @Test
    void 상신_초안_단계에서도_종결할_수_있다() {
        Case e = escalated();
        e.close(INVESTIGATOR, CloseReason.DUPLICATE, "중복 경보");
        assertThat(e.getStatus()).isEqualTo(CLOSED);

        Case d = drafted();
        d.close(INVESTIGATOR, CloseReason.NO_ACTION, "초안 정리와 함께 종결");
        assertThat(d.getStatus()).isEqualTo(CLOSED);
    }

    @Test
    void 종결_사유_설명이_비어_있으면_거부한다() {
        Case c = reviewing();

        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, ""))
                .isInstanceOf(CommentRequiredException.class);
        assertThat(c.getStatus()).isEqualTo(REVIEWING);
        assertThat(c.getCloseReason()).isNull();
    }

    @Test
    void 승인_대기에서는_종결할_수_없다() {
        Case c = pending();

        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, "종결"))
                .isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);
    }

    // ── 모의 제출 (REPORT) ──────────────────────────────────

    @Test
    void 승인된_케이스를_모의_제출하면_REPORTED와_REPORT_이력() {
        Case c = pending();
        c.approve(APPROVER, false);
        int before = c.getPendingHistories().size();

        c.report(APPROVER);

        assertThat(c.getStatus()).isEqualTo(REPORTED);
        assertThat(c.getPendingHistories()).hasSize(before + 1);
        CaseHistory last = c.getPendingHistories().get(c.getPendingHistories().size() - 1);
        assertThat(last.getAction()).isEqualTo(CaseHistoryAction.REPORT);
        assertThat(last.getFromStatus()).isEqualTo(APPROVED);
        assertThat(last.getToStatus()).isEqualTo(REPORTED);
    }

    @Test
    void 승인_전에는_모의_제출할_수_없고_REPORTED는_끝_상태() {
        Case c = pending();
        assertThatThrownBy(() -> c.report(APPROVER)).isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getStatus()).isEqualTo(PENDING_APPROVAL);

        c.approve(APPROVER, false);
        c.report(APPROVER);
        int before = c.getPendingHistories().size();
        assertThatThrownBy(() -> c.report(APPROVER)).isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, "종결")).isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    // ── 끝 상태 ──────────────────────────────────────────────

    @Test
    void 승인된_케이스는_어떤_행동도_거부한다() {
        Case c = pending();
        c.approve(APPROVER, false);
        int before = c.getPendingHistories().size();

        assertThatThrownBy(() -> c.attachDraft(NEW_DRAFT_ID, INVESTIGATOR)).isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, "종결")).isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> c.reject(APPROVER, false, NEED_REVISION, "반려")).isInstanceOf(InvalidCaseTransitionException.class);
        assertThat(c.getPendingHistories()).hasSize(before);
    }

    @Test
    void 종결된_케이스는_어떤_행동도_거부한다() {
        Case c = reviewing();
        c.close(INVESTIGATOR, CloseReason.NO_ACTION, "종결");

        assertThatThrownBy(() -> c.escalate(INVESTIGATOR, "다시")).isInstanceOf(InvalidCaseTransitionException.class);
        assertThatThrownBy(() -> c.reassign(INVESTIGATOR_B, ADMIN, null))
                .isInstanceOfSatisfying(InvalidCaseTransitionException.class, e -> {
                    assertThat(e.getMessage()).contains("REASSIGN").doesNotContain("→");   // "CLOSED → CLOSED" 가 아니라 동작 이름
                    assertThat(e.details()).containsEntry("action", CaseHistoryAction.REASSIGN).doesNotContainKey("to_status");
                });
        assertThatThrownBy(() -> c.close(INVESTIGATOR, CloseReason.NO_ACTION, "또")).isInstanceOf(InvalidCaseTransitionException.class);
    }

    // ── 한 바퀴: DB 제약과 같은 불변식 ───────────────────────

    @Test
    void 반려코드는_REJECTED일_때만_있다_V006_CHECK와_같은_규칙() {
        Case c = reviewing();
        c.escalate(INVESTIGATOR, "상신");
        c.attachDraft(DRAFT_ID, INVESTIGATOR);
        c.submitForApproval(INVESTIGATOR);
        assertThat(c.getRejectionReasonCode()).isNull();

        c.reject(APPROVER, false, NEED_REVISION, "보완");
        assertThat(c.getRejectionReasonCode()).isNotNull();

        c.reopenDraft(INVESTIGATOR);
        c.submitForApproval(INVESTIGATOR);
        c.approve(APPROVER, false);
        assertThat(c.getStatus()).isEqualTo(APPROVED);
        assertThat(c.getRejectionReasonCode()).isNull();

        assertThat(c.getPendingHistories()).extracting(CaseHistory::getAction).containsExactly(
                CaseHistoryAction.OPEN, CaseHistoryAction.ASSIGN, CaseHistoryAction.ESCALATE,
                CaseHistoryAction.ATTACH_DRAFT, CaseHistoryAction.SUBMIT_FOR_APPROVAL, CaseHistoryAction.REJECT,
                CaseHistoryAction.REOPEN_DRAFT, CaseHistoryAction.SUBMIT_FOR_APPROVAL, CaseHistoryAction.APPROVE);
    }

    @Test
    void 이력_목록은_밖에서_고칠_수_없다() {
        Case c = opened();

        assertThatThrownBy(() -> c.getPendingHistories().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── 이력 꺼내기 (저장은 application 이 CaseHistoryRepository 로) ─────────

    @Test
    void 저장_전_케이스의_이력은_꺼낼_수_없다() {
        Case c = opened();   // id 는 저장해야 생긴다

        assertThatThrownBy(c::takePendingHistories).isInstanceOf(IllegalStateException.class);
        assertThat(c.getPendingHistories()).hasSize(1);
    }

    @Test
    void 생성할_때_계좌와_법인을_받는다() {
        Case c = Case.open(CASE_NO, ALERT_ID, ACCOUNT_KEY, "E-77", SYSTEM);

        assertThat(c.getAccountKey()).isEqualTo(ACCOUNT_KEY);
        assertThat(c.getEntityId()).isEqualTo("E-77");
        assertThatThrownBy(() -> Case.open(CASE_NO, ALERT_ID, null, null, SYSTEM))
                .isInstanceOf(NullPointerException.class);
    }

    // ── 메모 (API-05, SCRUM-176) ─────────────────────────────

    @Test
    void 메모를_저장하면_상태는_그대로_NOTE_이력에_전문이_남는다() {
        Case c = reviewing();
        int before = c.getPendingHistories().size();

        assertThat(c.updateNotes("거래 목적 자료 요청 완료", null, INVESTIGATOR)).isTrue();

        assertThat(c.getStatus()).isEqualTo(REVIEWING);
        assertThat(c.getPendingHistories()).hasSize(before + 1);
        CaseHistory h = lastHistory(c);
        assertThat(h.getAction()).isEqualTo(CaseHistoryAction.NOTE);
        assertThat(h.getFromStatus()).isEqualTo(REVIEWING);
        assertThat(h.getToStatus()).isEqualTo(REVIEWING);
        assertThat(h.getComment()).isEqualTo("거래 목적 자료 요청 완료");
        assertThat(h.getCaseVersionBefore()).isEqualTo(c.getVersion());
        assertThat(h.getCreatedBy()).isEqualTo(INVESTIGATOR);
    }

    @Test
    void 같은_메모면_이력이_안_생기고_빈_문자열은_비우기_기록() {
        Case c = reviewing();
        int before = c.getPendingHistories().size();

        assertThat(c.updateNotes("같은 내용", "같은 내용", INVESTIGATOR)).isFalse();
        assertThat(c.updateNotes("", null, INVESTIGATOR)).isFalse();   // 메모가 없을 때 비우기는 바뀐 게 없음
        assertThat(c.getPendingHistories()).hasSize(before);

        assertThat(c.updateNotes("", "있던 메모", INVESTIGATOR)).isTrue();
        assertThat(lastHistory(c).getComment()).isEmpty();
    }

    @Test
    void 메모는_편집_가능_상태와_보완_반려에서만() {
        assertThat(escalated().updateNotes("a", null, INVESTIGATOR)).isTrue();
        assertThat(drafted().updateNotes("a", null, INVESTIGATOR)).isTrue();
        assertThat(rejected(NEED_REVISION).updateNotes("a", null, INVESTIGATOR)).isTrue();

        for (Case c : List.of(opened(), pending(), rejected(NOT_REPORTABLE))) {
            int before = c.getPendingHistories().size();
            assertThatThrownBy(() -> c.updateNotes("a", null, INVESTIGATOR))
                    .isInstanceOf(InvalidCaseTransitionException.class);
            assertThat(c.getPendingHistories()).hasSize(before);
        }
    }
}
