package com.team.aml.cases.infrastructure;

/** Spring Data 커스텀 조각 — 구현은 CaseBatchLockImpl (이름 규칙: 인터페이스 이름 + Impl). */
public interface CaseBatchLock {
    void lockOpenCasesStart();
}
