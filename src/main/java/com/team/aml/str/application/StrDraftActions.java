package com.team.aml.str.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.user.domain.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S2 available_actions — 지금 이 사용자가 STR 화면에서 누를 수 있는 버튼 (계약서 S2 토글 Q16, 4절 상태별 버튼).
 * 화면은 이 목록만 보고 버튼을 켜고, 변경 API 는 눌렀을 때 다시 검사한다(편의 값 — CaseAction 과 같은 원칙).
 *
 * <p>상태 판단을 str 이 새로 하지 않는다 — 조사역 버튼은 cases {@link CaseAction#availableFor}(= CaseStatus.allows 한 곳)에서
 * 옮겨 온다. 케이스 상세(C2) 버튼과 STR 화면 버튼이 따로 놀지 않게 (SCRUM-171). 프론트 strRules.strActionsFor 의 임시 계산과 같은 규칙.
 * <ul>
 *   <li>조사역(담당): GENERATE_DRAFT 가 되면 REVISE·REGENERATE, 그중 케이스 DRAFTED 면 VERIFY·SUBMIT 도 (S5·S6 은 DRAFTED 만).
 *       CLOSE_WITH_DRAFT 는 C2 와 같은 값</li>
 *   <li>승인자: 케이스 PENDING_APPROVAL 이고 지금·예전 담당이 아니면 APPROVE·REJECT (미결 32 — ApproveCase·RejectCase 와 같은 판단)</li>
 *   <li>그 밖(관리자·비담당·대체·잠긴 초안·다른 상태): []</li>
 * </ul>
 * SUBMIT 은 "상태상 가능"만 뜻한다 — 지금 본문 검증 통과 여부는 화면이 latest_verification.is_current·passed 로 함께 보고
 * 꺼진 이유 문구를 고른다(계약서 S6 켜는 조건). 아직 없는 동작: REPORT(P4)·PDF(S7·S8·P5) — 그 API 가 생길 때 더한다.
 */
final class StrDraftActions {

    static final String REVISE = "REVISE";
    static final String VERIFY = "VERIFY";
    static final String SUBMIT = "SUBMIT";
    static final String REGENERATE = "REGENERATE";
    static final String CLOSE_WITH_DRAFT = "CLOSE_WITH_DRAFT";
    static final String APPROVE = "APPROVE";
    static final String REJECT = "REJECT";

    private StrDraftActions() {}

    /**
     * @param draftId           보고 있는 초안 (DB id) — 케이스의 현재 초안이 아니면(재생성으로 대체) 버튼 없음
     * @param draftStatus       ACTIVE 가 아니면(LOCKED·SUPERSEDED) 버튼 없음
     * @param viewerWasAssignee 보는 사람이 이 케이스를 지금·예전에 담당했나 (cases WasCaseAssignee). 승인자일 때만 쓴다
     */
    static List<String> of(long draftId, StrDraftStatus draftStatus, Case c, long viewerId, Role viewerRole,
                           boolean viewerWasAssignee) {
        if (draftStatus != StrDraftStatus.ACTIVE || !Objects.equals(c.getCurrentDraftId(), draftId)) {
            return List.of();
        }
        if (viewerRole == Role.APPROVER) {
            return c.getStatus() == CaseStatus.PENDING_APPROVAL && !viewerWasAssignee ? List.of(APPROVE, REJECT) : List.of();
        }
        if (viewerRole != Role.INVESTIGATOR) {
            return List.of();
        }
        List<CaseAction> caseActions = CaseAction.availableFor(c, viewerId, viewerRole);   // 비담당이면 담당 동작이 빠져 온다
        List<String> actions = new ArrayList<>();
        if (caseActions.contains(CaseAction.GENERATE_DRAFT)) {
            actions.add(REVISE);
            if (c.getStatus() == CaseStatus.DRAFTED) {
                actions.add(VERIFY);
                actions.add(SUBMIT);
            }
            actions.add(REGENERATE);
        }
        if (caseActions.contains(CaseAction.CLOSE_WITH_DRAFT)) {
            actions.add(CLOSE_WITH_DRAFT);
        }
        return List.copyOf(actions);
    }
}
