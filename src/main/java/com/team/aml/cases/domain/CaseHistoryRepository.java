package com.team.aml.cases.domain;

import java.util.List;

/** 추가만 하는 이력 (01_code_rules 원칙 3). UPDATE·DELETE 메서드를 두지 않는다. */
public interface CaseHistoryRepository {
    <S extends CaseHistory> List<S> saveAll(Iterable<S> histories);
    List<CaseHistory> findByCaseIdOrderByIdAsc(Long caseId);

    /** 상세 화면의 최근 이력 20건, 최신이 먼저 (CASES 명세 API-02 — 전체는 API-11). */
    List<CaseHistory> findTop20ByCaseIdOrderByIdDesc(Long caseId);

    /** 이 사람이 이 케이스를 받은 적이 있나 — ASSIGN·REASSIGN 의 to_assignee_id (V010, 자기 승인 막기). */
    boolean existsByCaseIdAndToAssigneeId(Long caseId, Long toAssigneeId);
}
