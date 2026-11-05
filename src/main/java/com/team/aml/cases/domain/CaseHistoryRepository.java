package com.team.aml.cases.domain;

import java.util.List;

/** 추가만 하는 이력 (01_code_rules 원칙 3). UPDATE·DELETE 메서드를 두지 않는다. */
public interface CaseHistoryRepository {
    <S extends CaseHistory> List<S> saveAll(Iterable<S> histories);
    List<CaseHistory> findByCaseIdOrderByIdAsc(Long caseId);
}
