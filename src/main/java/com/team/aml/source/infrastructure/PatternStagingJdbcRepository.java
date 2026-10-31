package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.PatternMatchStats;
import com.team.aml.source.domain.PatternRow;
import com.team.aml.source.domain.PatternStagingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** patterns_staging 저장과 transactions_staging 매칭. 거래 행 전체 필드를 매칭 키로 쓴다. */
@Repository
public class PatternStagingJdbcRepository implements PatternStagingRepository {

    private static final int BATCH_SIZE = 1000;

    private static final String KEY_COLUMNS = """
            timestamp_text, from_bank, from_account, to_bank, to_account, amount_received,
            receiving_currency, amount_paid, payment_currency, payment_format, is_laundering""";

    /** 508만 행에서 해시 조인을 쓰도록 = 로 비교한다. NULL 필드는 미매칭으로 집계된다. */
    static final String MATCH_CONDITION = """
            t.timestamp_text = p.timestamp_text and t.from_bank = p.from_bank
            and t.from_account = p.from_account and t.to_bank = p.to_bank
            and t.to_account = p.to_account and t.amount_received = p.amount_received
            and t.receiving_currency = p.receiving_currency and t.amount_paid = p.amount_paid
            and t.payment_currency = p.payment_currency and t.payment_format = p.payment_format
            and t.is_laundering = p.is_laundering""";

    /** 거래 LEFT JOIN용. 키가 같은 패턴 행이 있어도 거래 행 수가 늘지 않는다. */
    static final String DISTINCT_PATTERNS = """
            select distinct on (%1$s) pattern_type, pattern_id, %1$s
            from ops.patterns_staging
            order by %1$s, pattern_id""".formatted(KEY_COLUMNS);

    private static final String INSERT = """
            insert into ops.patterns_staging(pattern_id, pattern_type, %s)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(KEY_COLUMNS);

    private static final String MATCH_STATS = """
            with pattern_keys as (
                select %1$s, count(*) as key_count, row_number() over () as key_no
                from ops.patterns_staging
                group by %1$s
            ), matches as (
                select p.key_no, p.key_count, count(t.row_no) as match_count
                from pattern_keys p
                left join ops.transactions_staging t on %2$s
                group by p.key_no, p.key_count
            )
            select coalesce(sum(key_count) filter (where match_count = 1 and key_count = 1), 0) as matched,
                   coalesce(sum(key_count) filter (where match_count = 0), 0) as unmatched,
                   coalesce(sum(key_count) filter (where match_count > 1 or (match_count = 1 and key_count > 1)), 0) as multi_matched
            from matches""".formatted(KEY_COLUMNS, MATCH_CONDITION);

    private final JdbcTemplate jdbc;

    public PatternStagingJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void clear() {
        jdbc.execute("truncate table ops.patterns_staging");
    }

    @Override
    public void saveAll(List<PatternRow> rows) {
        jdbc.batchUpdate(INSERT, rows, BATCH_SIZE, (ps, row) -> {
            ps.setString(1, row.patternId());
            ps.setString(2, row.patternType().name());
            ps.setString(3, row.timestampText());
            ps.setString(4, row.fromBank());
            ps.setString(5, row.fromAccount());
            ps.setString(6, row.toBank());
            ps.setString(7, row.toAccount());
            ps.setString(8, row.amountReceived());
            ps.setString(9, row.receivingCurrency());
            ps.setString(10, row.amountPaid());
            ps.setString(11, row.paymentCurrency());
            ps.setString(12, row.paymentFormat());
            ps.setString(13, row.isLaundering());
        });
    }

    @Override
    public PatternMatchStats matchStats() {
        return jdbc.queryForObject(MATCH_STATS, (rs, rowNum) -> new PatternMatchStats(
                rs.getLong("matched"), rs.getLong("unmatched"), rs.getLong("multi_matched")));
    }

    /** P000370 → 370. 적재된 패턴이 없으면 0. */
    @Override
    public long lastEventNumber() {
        return jdbc.queryForObject(
                "select coalesce(max(substring(pattern_id from 2)::bigint), 0) from ops.transactions",
                Long.class);
    }
}
