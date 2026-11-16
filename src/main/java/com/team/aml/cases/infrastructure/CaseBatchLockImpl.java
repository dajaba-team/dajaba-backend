package com.team.aml.cases.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 케이스 생성 배치 시작을 줄 세우는 advisory lock (SCRUM-238). 트랜잭션이 끝나면 풀린다.
 * V014 로 성공한 날도 다시 돌 수 있어 batch_runs 고유 인덱스가 동시 시작을 막아 주지 않는다 — 점수 배치(5_005_002)와 같은 방식.
 * 키는 적재 4_004_001·룰 스캔 5_005_001·점수 5_005_002 와 다른 값(V006 cases).
 */
class CaseBatchLockImpl implements CaseBatchLock {

    private static final long OPEN_CASES_START_LOCK = 6_006_001L;

    private final JdbcTemplate jdbc;

    CaseBatchLockImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOpenCasesStart() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, OPEN_CASES_START_LOCK);
    }
}
