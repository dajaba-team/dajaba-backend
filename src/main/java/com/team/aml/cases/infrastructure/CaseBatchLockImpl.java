package com.team.aml.cases.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 케이스 생성 배치 시작을 줄 세우는 advisory lock (SCRUM-238). 트랜잭션이 끝나면 풀린다.
 * V014 로 성공한 날도 다시 돌 수 있어 batch_runs 고유 인덱스가 동시 시작을 막아 주지 않는다 — 점수 배치(5_005_002)와 같은 방식.
 * 키는 적재 4_004_001·룰 스캔 5_005_001·점수 5_005_002 와 다른 값(V006 cases).
 */
class CaseBatchLockImpl implements CaseBatchLock {

    private static final long OPEN_CASES_START_LOCK = 6_006_001L;
    /**
     * 실행 잠금은 두 칸짜리 키(분류, batch_run_id). PostgreSQL 에서 두 칸 키와 한 칸 키는 서로 겹치지 않아 위 키들과 안 부딪힌다.
     * batch_run_id 는 int 범위로 접는다 — 21억 차이 나는 두 실행이 같은 키가 될 뿐이라(서로 기다리지 않고 한쪽이 건너뜀) 실제로는 문제없다.
     */
    private static final int OPEN_CASES_RUN_LOCK_CLASS = 6_006;

    private final JdbcTemplate jdbc;

    CaseBatchLockImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOpenCasesStart() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, OPEN_CASES_START_LOCK);
    }

    @Override
    public boolean tryLockOpenCasesRun(long batchRunId) {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?, (? % 2147483647)::int)",
                Boolean.class, OPEN_CASES_RUN_LOCK_CLASS, batchRunId);
        return Boolean.TRUE.equals(locked);
    }
}
