package com.team.aml.cases.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 이력 동작 → 알림 종류 (SCRUM-355, 설계 6.5 표). DB 없이. */
class NotificationTypeTest {

    @Test
    void 정상_알림이_되는_동작은_설계_표대로() {
        assertThat(NotificationType.of(CaseHistoryAction.ASSIGN, true)).contains(NotificationType.CASE_ASSIGNED);
        assertThat(NotificationType.of(CaseHistoryAction.REASSIGN, true)).contains(NotificationType.CASE_REASSIGNED_TO_ME);
        assertThat(NotificationType.of(CaseHistoryAction.REASSIGN, false)).contains(NotificationType.CASE_REASSIGNED_AWAY);
        assertThat(NotificationType.of(CaseHistoryAction.SUBMIT_FOR_APPROVAL, false))
                .contains(NotificationType.APPROVAL_REQUESTED);
        assertThat(NotificationType.of(CaseHistoryAction.APPROVE, false)).contains(NotificationType.STR_APPROVED);
        assertThat(NotificationType.of(CaseHistoryAction.REJECT, false)).contains(NotificationType.STR_REJECTED);
        assertThat(NotificationType.of(CaseHistoryAction.REPORT, false)).contains(NotificationType.STR_REPORTED);
    }

    @Test
    void 경계_OPEN_메모_에스컬레이션_종결_초안_동작은_알림이_아니다() {
        for (CaseHistoryAction a : EnumSet.of(CaseHistoryAction.OPEN, CaseHistoryAction.NOTE, CaseHistoryAction.ESCALATE,
                CaseHistoryAction.CLOSE, CaseHistoryAction.ATTACH_DRAFT, CaseHistoryAction.REOPEN_DRAFT)) {
            assertThat(NotificationType.of(a, true)).as(a.name()).isEmpty();
        }
    }

    @Test
    void 실패_초안_번호는_승인_요청과_STR_3종에만() {
        Set<NotificationType> withDraft = EnumSet.noneOf(NotificationType.class);
        for (NotificationType t : NotificationType.values()) {
            if (t.carriesDraft()) {
                withDraft.add(t);
            }
        }
        assertThat(withDraft).containsExactlyInAnyOrder(NotificationType.APPROVAL_REQUESTED,
                NotificationType.STR_APPROVED, NotificationType.STR_REJECTED, NotificationType.STR_REPORTED);
    }
}
