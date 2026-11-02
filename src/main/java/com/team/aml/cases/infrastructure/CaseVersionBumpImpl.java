package com.team.aml.cases.infrastructure;

import com.team.aml.cases.domain.Case;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * EntityManager.lock(PESSIMISTIC_FORCE_INCREMENT) — 바로 UPDATE 로 version 을 1 올린다(롤백되면 같이 되돌아감).
 * findForUpdate 로 이미 FOR UPDATE 잠금을 쥔 행이라 OPTIMISTIC_FORCE_INCREMENT 는 "더 약한 잠금"으로 보고
 * Hibernate 가 무시한다 — 그래서 PESSIMISTIC 쪽을 쓴다 (BumpCaseVersion 테스트로 확인).
 */
class CaseVersionBumpImpl implements CaseVersionBump {

    private final EntityManager em;

    CaseVersionBumpImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public void bumpVersion(Case c) {
        em.lock(c, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
}
