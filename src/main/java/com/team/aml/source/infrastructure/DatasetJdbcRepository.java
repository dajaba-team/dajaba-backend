package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.DatasetRepository;
import com.team.aml.source.domain.DatasetStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** 적재 시작 판단과 적재 후 집계. 정답 라벨은 여기서 건수로만 센다 (레퍼런스 §10-10). */
@Repository
public class DatasetJdbcRepository implements DatasetRepository {

    /** 적재 시작 판단을 줄 세우는 advisory lock 키. 트랜잭션이 끝나면 풀린다. */
    private static final long LOAD_START_LOCK = 4_004_001L;

    private static final String STATS = """
            select (select count(*) from ops.transactions) as transactions,
                   (select count(*) from ops.transactions where is_laundering) as laundering,
                   (select count(*) from ops.transactions where is_self_transfer) as self_transfers,
                   (select count(*) from ops.transactions where pattern_type is not null) as pattern_transactions,
                   (select count(distinct pattern_id) from ops.transactions) as pattern_events,
                   (select min(ts) from ops.transactions) as first_ts,
                   (select max(ts) from ops.transactions) as last_ts,
                   (select count(*) from ops.accounts) as accounts,
                   (select count(*) from ops.entities) as entities,
                   (select count(*) from (
                        select from_account_key as account_key from ops.transactions
                        union
                        select to_account_key from ops.transactions) k
                    where not exists (select 1 from ops.accounts a where a.account_key = k.account_key)) as orphan_accounts""";

    private final JdbcTemplate jdbc;

    public DatasetJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockLoadStart() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, LOAD_START_LOCK);
    }

    @Override
    public boolean hasTransactions() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ops.transactions)", Boolean.class));
    }

    @Override
    public boolean hasRunningLoad() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from ops.batch_runs where batch_type = 'LOAD_DATASET' and status = 'RUNNING')""",
                Boolean.class));
    }

    @Override
    public DatasetStats collectStats() {
        return jdbc.queryForObject(STATS, (rs, rowNum) -> new DatasetStats(
                rs.getLong("transactions"), rs.getLong("laundering"), rs.getLong("self_transfers"),
                rs.getLong("pattern_transactions"), rs.getLong("pattern_events"),
                rs.getObject("first_ts", LocalDateTime.class), rs.getObject("last_ts", LocalDateTime.class),
                rs.getLong("accounts"), rs.getLong("entities"), rs.getLong("orphan_accounts")));
    }
}
