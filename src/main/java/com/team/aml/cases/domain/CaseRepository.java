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

    /**
     * 비관적 쓰기 잠금(SELECT … FOR UPDATE)으로 읽는다. 트랜잭션이 끝날 때까지 같은 케이스를 바꾸려는
     * 다른 트랜잭션은 기다린다. 다른 도메인(str)과 함께 케이스를 바꾸는 유스케이스(AttachDraftToCase 등)가 쓴다.
     * 낙관적 락(@Version)은 그대로 — 잠금은 "동시에", version 은 "AI 호출 전에 읽은 뒤로 바뀌었는지"를 본다.
     */
    Optional<Case> findForUpdate(Long id);

    /**
     * 상태·칸은 그대로 두고 cases.version 만 1 올린다 (PESSIMISTIC_FORCE_INCREMENT, 트랜잭션이 롤백되면 같이 되돌아간다).
     * str 이 케이스 행을 안 바꾸는 작업(수정본 저장 등)을 할 때 부른다 — 동시에 들어온 승인 요청 같은
     * "서로 다른 행만 바꾸는 두 요청"이 같은 케이스 version 에서 부딪히게 하려는 것 (NFR-APR-02, 미결 20).
     */
    void bumpVersion(Case c);
    Optional<Case> findByCaseNo(String caseNo);
    boolean existsByAlertId(Long alertId);
}
