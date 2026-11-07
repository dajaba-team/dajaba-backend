package com.team.aml.cases.application;

import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseHistoryRepository;
import com.team.aml.cases.domain.CaseNotFoundException;
import com.team.aml.cases.domain.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * str 에 주는 공개 조회 — 이 사람이 이 케이스를 지금 맡고 있거나 예전에 맡은 적이 있나 (미결 32, FR-CASE-11·FR-APR-04·05).
 * 승인·반려 유스케이스와 초안 조회(S2)·승인함(P1)의 available_actions 가 같은 판단을 쓰도록 이것 하나만 부른다.
 * true 면 승인·반려 버튼을 주지 않고, 그래도 요청이 오면 Case.approve·reject 가 409 SELF_APPROVAL.
 *
 * <p>판정: 지금 담당(cases.assignee_id) 이거나, 받은 사람(case_history.to_assignee_id, V010)으로 남은 이력이 있다.
 * 지금 담당을 따로 보는 이유 — V010 전에 쌓인 ASSIGN·REASSIGN 행은 받은 사람 칸이 비어 있다.
 *
 * <p>바깥 트랜잭션(승인 등)이 있으면 그 안에서, 없으면 읽기 전용으로 읽는다.
 */
@Service
public class WasCaseAssignee {

    private final CaseRepository cases;
    private final CaseHistoryRepository histories;

    public WasCaseAssignee(CaseRepository cases, CaseHistoryRepository histories) {
        this.cases = cases;
        this.histories = histories;
    }

    /** 없는 케이스면 404 CASE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public boolean execute(long caseId, long userId) {
        Case c = cases.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        return Objects.equals(c.getAssigneeId(), userId) || histories.existsByCaseIdAndToAssigneeId(caseId, userId);
    }
}
