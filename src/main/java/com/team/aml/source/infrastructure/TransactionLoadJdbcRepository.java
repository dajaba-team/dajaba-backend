package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.TransactionFileFormatException;
import com.team.aml.source.domain.TransactionLoadRepository;
import com.team.aml.source.domain.TransactionStagingCheck;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;

/** transactions_staging COPY와 transactions 변환. 패턴은 패턴 스테이징에서 LEFT JOIN으로 함께 넣는다. */
@Repository
public class TransactionLoadJdbcRepository implements TransactionLoadRepository {

    private static final String TIMESTAMP_SHAPE = "'^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}$'";

    private static final String AMOUNT_SHAPE = "'^[0-9]+([.][0-9]+)?$'";

    private static final String BANK_ID_SHAPE = "'^[0-9]+$'";

    private static final String AMOUNT_SCALE = """
            select numeric_scale from information_schema.columns
            where table_schema = 'ops' and table_name = 'transactions' and column_name = 'amount_paid'""";

    /** %1$s 시각 형식, %2$s 금액 형식, %3$d 금액 컬럼 소수 자릿수, %4$s 은행 ID 형식. CSV의 ""는 NULL이 아니므로 공백까지 검사한다. */
    private static final String CHECK = """
            select count(*) as row_count,
                   count(*) filter (where nullif(btrim(from_bank), '') is null
                                       or nullif(btrim(from_account), '') is null
                                       or nullif(btrim(to_bank), '') is null
                                       or nullif(btrim(to_account), '') is null
                                       or nullif(btrim(receiving_currency), '') is null
                                       or nullif(btrim(payment_currency), '') is null
                                       or nullif(btrim(payment_format), '') is null) as missing_required,
                   count(*) filter (where not coalesce(
                       from_bank ~ %4$s and to_bank ~ %4$s, false)) as invalid_bank_ids,
                   count(*) filter (where not coalesce(
                       timestamp_text ~ %1$s
                       and pg_input_is_valid(replace(timestamp_text, '/', '-'), 'timestamp'), false)) as invalid_timestamps,
                   count(*) filter (where not coalesce(
                       amount_received ~ %2$s and amount_paid ~ %2$s, false)) as invalid_amounts,
                   count(*) filter (where not coalesce(is_laundering in ('0', '1'), false)) as invalid_labels,
                   coalesce(sum(
                       case when amount_received ~ %2$s
                             and amount_received::numeric <> round(amount_received::numeric, %3$d) then 1 else 0 end
                     + case when amount_paid ~ %2$s
                             and amount_paid::numeric <> round(amount_paid::numeric, %3$d) then 1 else 0 end), 0) as amounts_rounded
            from ops.transactions_staging""";

    // TODO(안건 19): 11/2 numeric(20,6)으로 변경 결정, V005_2에서 바꾼다. 그 전에는 소수 2자리를 넘으면 반올림된다.
    // transaction_id는 generated always라 열 목록에서 뺀다. 값을 넣으면 DB가 거부한다 (안건 1).
    private static final String INSERT = """
            insert into ops.transactions(
                ts, from_bank_id, from_bank_id_raw, from_account_no, from_account_key,
                to_bank_id, to_bank_id_raw, to_account_no, to_account_key,
                amount_received, currency_received, amount_paid, currency_paid, payment_format,
                is_laundering, is_self_transfer, pattern_type, pattern_id, source_file_id, source_row_number)
            select replace(t.timestamp_text, '/', '-')::timestamp,
                   %1$s, t.from_bank, t.from_account, %1$s || '_' || t.from_account,
                   %2$s, t.to_bank, t.to_account, %2$s || '_' || t.to_account,
                   t.amount_received::numeric, t.receiving_currency, t.amount_paid::numeric, t.payment_currency, t.payment_format,
                   t.is_laundering = '1',
                   %1$s || '_' || t.from_account = %2$s || '_' || t.to_account,
                   p.pattern_type, p.pattern_id, ?, t.source_row_number
            from (
                select s.*, row_number() over (order by s.row_no) as source_row_number
                from ops.transactions_staging s
            ) t
            left join (%3$s) p on %4$s""".formatted(
            AccountLoadJdbcRepository.normalizedBankId("t.from_bank"),
            AccountLoadJdbcRepository.normalizedBankId("t.to_bank"),
            PatternStagingJdbcRepository.DISTINCT_PATTERNS,
            PatternStagingJdbcRepository.MATCH_CONDITION);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public TransactionLoadJdbcRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public void clearStaging() {
        jdbc.execute("truncate table ops.transactions_staging");
    }

    @Override
    public void copyToStaging(Reader rowsWithoutHeader) throws IOException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
                    copy ops.transactions_staging(
                        timestamp_text, from_bank, from_account, to_bank, to_account, amount_received,
                        receiving_currency, amount_paid, payment_currency, payment_format, is_laundering)
                    from stdin with (format csv)""", rowsWithoutHeader);
        } catch (SQLException e) {
            // PostgreSQL 메시지에 문제 행이 들어가므로 원인 예외를 붙이지 않는다.
            throw new TransactionFileFormatException("스테이징 적재 실패 (SQLState " + e.getSQLState() + ")");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public TransactionStagingCheck checkStaging() {
        int scale = jdbc.queryForObject(AMOUNT_SCALE, Integer.class);
        String sql = CHECK.formatted(TIMESTAMP_SHAPE, AMOUNT_SHAPE, scale, BANK_ID_SHAPE);
        return jdbc.queryForObject(sql, (rs, rowNum) -> new TransactionStagingCheck(
                rs.getLong("row_count"), rs.getLong("missing_required"), rs.getLong("invalid_bank_ids"),
                rs.getLong("invalid_timestamps"), rs.getLong("invalid_amounts"), rs.getLong("invalid_labels"),
                rs.getLong("amounts_rounded")));
    }

    @Override
    public long insertFromStaging(long sourceFileId) {
        return jdbc.update(INSERT, sourceFileId);
    }
}
