package com.team.aml.cases.domain;

import java.util.Optional;

/**
 * 인터페이스는 domain 에, JPA 구현은 infrastructure 에 (레퍼런스 4.1, UserRepository 와 같음).
 * 케이스는 지우지 않는다 — delete 메서드를 두지 않는다 (V006 에서 DELETE 회수).
 * 저장 뒤 이력은 case.takePendingHistories() 를 CaseHistoryRepository 로 같은 트랜잭션에서 넣는다.
 */
public interface CaseRepository {
    Case save(Case c);
    Optional<Case> findById(Long id);
    Optional<Case> findByCaseNo(String caseNo);
    boolean existsByAlertId(Long alertId);
}
