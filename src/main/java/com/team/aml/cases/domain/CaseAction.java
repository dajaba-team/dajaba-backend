package com.team.aml.cases.domain;

import com.team.aml.user.domain.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 상세 응답 available_actions — 지금 이 사용자가 누를 수 있는 버튼 (계약서 v0.2 §4, 지윤 11/4 8개 확정).
 * 화면은 이 목록만 보고 버튼을 켜고, 변경 API 는 눌렀을 때 다시 검사한다(편의 값).
 * 상태 판단은 CaseStatus.allows 한 곳을 쓴다 — 버튼과 실제 전이·업무 메서드가 따로 놀지 않게(SCRUM-171).
 * 여기서는 누가(역할·담당자)와 초안 유무만 더 본다.
 */
public enum CaseAction {
    ASSIGN,            // OPEN — 조사역은 본인, ADMIN 은 지정
    REASSIGN,          // ADMIN, REVIEWING·ESCALATED·DRAFTED·REJECTED
    UPDATE_NOTES,      // 담당, 편집 가능 상태
    REFRESH_SUMMARY,   // 담당, 편집 가능 상태
    ESCALATE,          // 담당, REVIEWING
    CLOSE,             // 담당, 종결 가능 + 초안 없음 → POST /cases/{case_no}/close
    CLOSE_WITH_DRAFT,  // 담당, 종결 가능 + 초안 있음 → POST /cases/{case_no}/close-with-draft (str)
    GENERATE_DRAFT;    // 담당, ESCALATED·DRAFTED(재생성)·REJECTED+NEED_REVISION

    /** 화면에 줄 순서는 enum 순서. 로그인 사용자의 id·역할로 계산한다. */
    public static List<CaseAction> availableFor(Case c, long viewerId, Role viewerRole) {
        List<CaseAction> actions = new ArrayList<>();
        CaseStatus status = c.getStatus();
        RejectionReasonCode code = c.getRejectionReasonCode();

        if (status.allows(ASSIGN, code) && (viewerRole == Role.INVESTIGATOR || viewerRole == Role.ADMIN)) {
            actions.add(ASSIGN);
        }
        if (viewerRole == Role.ADMIN && status.allows(REASSIGN, code)) {
            actions.add(REASSIGN);
        }
        if (!Objects.equals(c.getAssigneeId(), viewerId)) {
            return actions;   // 아래는 담당 조사역만
        }
        for (CaseAction action : List.of(UPDATE_NOTES, REFRESH_SUMMARY, ESCALATE)) {
            if (status.allows(action, code)) {
                actions.add(action);
            }
        }
        CaseAction close = c.getCurrentDraftId() == null ? CLOSE : CLOSE_WITH_DRAFT;
        if (status.allows(close, code)) {
            actions.add(close);
        }
        if (status.allows(GENERATE_DRAFT, code)) {
            actions.add(GENERATE_DRAFT);
        }
        return actions;
    }
}
