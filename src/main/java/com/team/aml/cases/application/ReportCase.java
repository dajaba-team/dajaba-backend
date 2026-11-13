package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryAction;
import com.team.aml.shared.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 에 주는 공개 유스케이스 — 모의 제출 APPROVED → REPORTED (CASES 명세 §2.2·§6.2 ReportCase, API P4, SCRUM-234).
 * 실제 기관 전송은 없다. str 모의 제출 유스케이스(POST /approvals/{draft_no}/report)와 같은 트랜잭션에서만 부른다
 * (MANDATORY) — 제출 기록·케이스 REPORTED·REPORT 이력이 함께 성공하거나 롤백된다.
 *
 * <p>검사 순서: 케이스 잠금 조회(404 CASE_NOT_FOUND) → version(409 CONCURRENT_UPDATE)
 * → 현재 초안이 draftId(다른 초안이면 409 DRAFT_SUPERSEDED details.current_draft_no, 현재 초안이 없으면
 * 409 INVALID_CASE_TRANSITION details.draft_no — SCRUM-223) → 상태(APPROVED 가 아니면 409 INVALID_CASE_TRANSITION).
 * REPORTED 는 끝 상태라 같은 케이스를 두 번 제출하면 두 번째는 409.
 * 제출자 역할(APPROVER)은 SecurityConfig 의 POST /approvals/** 와 str 이 본다(ApproveCase 와 같음).
 * 승인과 달리 지금·예전 담당 검사는 하지 않는다 — 내용 판단(승인)은 이미 끝났고 제출은 승인된 보고서를 내보내는 일이라서.
 * 〔확인 필요〕 제출자를 승인한 사람으로 한정할지는 str 모의 제출 명세(SCRUM-240)와 맞춘다.
 *
 * <p>str 이 제출 기록처럼 케이스 FK 행을 넣기 <b>전에</b> 부른다(SubmitCaseForApproval 주석의 "부르는 순서").
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReportCase {

    private final CaseChanges changes;
    private final CurrentUser currentUser;

    public ReportCase(CaseChanges changes, CurrentUser currentUser) {
        this.changes = changes;
        this.currentUser = currentUser;
    }

    /**
     * @param draftId         제출하는 초안 id(승인으로 LOCKED 된 초안). 케이스의 현재 초안이어야 한다
     * @param expectedVersion 화면이 들고 온 cases.version(case_version)
     * @return 새 cases.version — str 이 응답의 case_version 에 그대로 싣는다
     */
    public long execute(long caseId, long draftId, long expectedVersion) {
        Case c = changes.lock(caseId, expectedVersion);
        long actorId = currentUser.require().userId();
        c.requireCurrentDraft(draftId, CaseHistoryAction.REPORT);
        c.report(actorId);
        return changes.saveAndGetVersion(c);
    }
}
