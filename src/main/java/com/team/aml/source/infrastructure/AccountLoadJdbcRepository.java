package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.AccountFileFormatException;
import com.team.aml.source.domain.AccountLoadRepository;
import com.team.aml.source.domain.AccountStagingCheck;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;

/** accounts_staging COPY와 entities·accounts INSERT. 원천 UPDATE가 막혀 있어 파생값은 INSERT에서 모두 계산한다. */
@Repository
public class AccountLoadJdbcRepository implements AccountLoadRepository {

    private static final String ENTITY_PREFIX = "regexp_replace(entity_name, ' #.*$', '')";

    private static final String BANK_COUNTRY_NAME = "substring(bank_name from '^(.+) Bank #[0-9]+$')";

    /** 실제 파일의 "{국가} Bank #n"에서 확인한 국가. 그 외(Crytpo 등)는 null이다 (서식 5-10). */
    private static final String BANK_COUNTRIES = """
            'Australia', 'Austria', 'Belgium', 'Brazil', 'Canada', 'China', 'Croatia', 'Cyprus',
            'Estonia', 'Finland', 'France', 'Germany', 'Greece', 'India', 'Ireland', 'Israel',
            'Italy', 'Japan', 'Latvia', 'Lithuania', 'Luxembourg', 'Malta', 'Mexico', 'Netherlands',
            'Portugal', 'Russia', 'Saudi Arabia', 'Slovakia', 'Slovenia', 'Spain', 'Switzerland', 'UK'""";

    /** CSV의 ""는 NULL이 아니라 빈 문자열이므로 공백까지 함께 검사한다. */
    private static final String CHECK = """
            select count(*) as row_count,
                   count(*) filter (where nullif(btrim(bank_id), '') is null
                                       or nullif(btrim(account_number), '') is null
                                       or nullif(btrim(entity_id), '') is null
                                       or nullif(btrim(entity_name), '') is null) as missing_required,
                   count(*) filter (where not coalesce(bank_id ~ '^[0-9]+$', false)) as invalid_bank_ids,
                   count(*) filter (where %1$s not in ('Corporation', 'Partnership', 'Sole Proprietorship',
                                                       'Individual', 'Country', 'Direct')) as unknown_entity_types,
                   (select count(*) from (
                        select entity_id from ops.accounts_staging
                        group by entity_id having count(distinct entity_name) > 1) c) as entity_name_conflicts,
                   (select count(*) from (
                        select %2$s, account_number from ops.accounts_staging
                        group by 1, 2 having count(distinct entity_id) > 1) c) as account_owner_conflicts
            from ops.accounts_staging""".formatted(ENTITY_PREFIX, normalizedBankId("bank_id"));

    private static final String INSERT_ENTITIES = """
            insert into ops.entities(entity_id, entity_name, entity_type)
            select distinct on (entity_id) entity_id, entity_name,
                   case %s
                       when 'Corporation' then 'CORPORATION'
                       when 'Partnership' then 'PARTNERSHIP'
                       when 'Sole Proprietorship' then 'SOLE_PROPRIETORSHIP'
                       when 'Individual' then 'INDIVIDUAL'
                       when 'Country' then 'COUNTRY'
                       when 'Direct' then 'DIRECT'
                   end
            from ops.accounts_staging
            order by entity_id
            on conflict (entity_id) do nothing""".formatted(ENTITY_PREFIX);

    private static final String INSERT_ACCOUNTS = """
            insert into ops.accounts(account_key, bank_id, bank_name, account_no, entity_id, bank_country)
            select %1$s || '_' || account_number, %1$s, bank_name, account_number, entity_id,
                   case when %2$s in (%3$s) then %2$s end
            from ops.accounts_staging
            on conflict (account_key) do nothing"""
            .formatted(normalizedBankId("bank_id"), BANK_COUNTRY_NAME, BANK_COUNTRIES);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public AccountLoadJdbcRepository(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** 앞 0 제거 (미결 6). 전부 0이면 0을 남긴다. 거래 적재도 이 규칙을 쓴다. */
    static String normalizedBankId(String column) {
        return "regexp_replace(" + column + ", '^0+(?=.)', '')";
    }

    @Override
    public void clearStaging() {
        jdbc.execute("truncate table ops.accounts_staging");
    }

    @Override
    public void copyToStaging(Reader rowsWithoutHeader) throws IOException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
                    copy ops.accounts_staging(bank_name, bank_id, account_number, entity_id, entity_name)
                    from stdin with (format csv)""", rowsWithoutHeader);
        } catch (SQLException e) {
            // PostgreSQL 메시지에 문제 행이 들어가므로 원인 예외를 붙이지 않는다.
            throw new AccountFileFormatException("스테이징 적재 실패 (SQLState " + e.getSQLState() + ")");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public AccountStagingCheck checkStaging() {
        return jdbc.queryForObject(CHECK, (rs, rowNum) -> new AccountStagingCheck(
                rs.getLong("row_count"), rs.getLong("missing_required"), rs.getLong("invalid_bank_ids"),
                rs.getLong("unknown_entity_types"), rs.getLong("entity_name_conflicts"),
                rs.getLong("account_owner_conflicts")));
    }

    @Override
    public long insertEntities() {
        return jdbc.update(INSERT_ENTITIES);
    }

    @Override
    public long insertAccounts() {
        return jdbc.update(INSERT_ACCOUNTS);
    }
}
