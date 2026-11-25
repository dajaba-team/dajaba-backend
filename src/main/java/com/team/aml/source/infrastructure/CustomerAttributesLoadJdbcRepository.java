package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.CustomerAttributesFileFormatException;
import com.team.aml.source.domain.CustomerAttributesLoadRepository;
import com.team.aml.source.domain.CustomerAttributesLoadResult;
import org.postgresql.PGConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * KYC 파일을 임시 표(트랜잭션 끝에 사라짐)에 COPY한 뒤 customer_attributes로 INSERT한다 (SCRUM-430).
 * 법인이 없는 행·이미 들어 있는 entity_id는 건너뛴다 — 파일은 선택이라 KYC 때문에 데이터 적재 전체가 실패하지 않게.
 * 값 형식(날짜·국가코드 2자리·위험등급 LOW/MEDIUM/HIGH·필수값)이 틀리면 실패한다. 원천 UPDATE가 막혀 있어 INSERT만 쓴다.
 */
@Repository
public class CustomerAttributesLoadJdbcRepository implements CustomerAttributesLoadRepository {

    private static final String CREATE_STAGING = """
            create temporary table if not exists kyc_staging (
                entity_id text, industry text, risk_level text,
                account_purpose text, onboarded_at text, country_code text
            ) on commit drop""";

    /** CSV에서 빈칸은 NULL로 들어오고, 따옴표 빈 문자열("")·공백만 있는 값도 NULL로 바꾼다 — 계좌 개설 목적(V020). */
    private static final String INSERT = """
            insert into ops.customer_attributes(entity_id, industry, risk_level, account_purpose, onboarded_at, country_code)
            select btrim(s.entity_id), btrim(s.industry), btrim(s.risk_level), nullif(btrim(s.account_purpose), ''),
                   cast(btrim(s.onboarded_at) as date), btrim(s.country_code)
            from pg_temp.kyc_staging s
            where exists (select 1 from ops.entities e where e.entity_id = btrim(s.entity_id))
            on conflict (entity_id) do nothing""";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public CustomerAttributesLoadJdbcRepository(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public CustomerAttributesLoadResult load(Reader rowsWithoutHeader) throws IOException {
        jdbc.execute(CREATE_STAGING);
        jdbc.execute("truncate table pg_temp.kyc_staging");
        long fileRows;
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            fileRows = connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
                    copy pg_temp.kyc_staging(entity_id, industry, risk_level, account_purpose, onboarded_at, country_code)
                    from stdin with (format csv)""", rowsWithoutHeader);
        } catch (SQLException e) {
            // PostgreSQL 메시지에 문제 행이 들어가므로 원인 예외를 붙이지 않는다.
            throw new CustomerAttributesFileFormatException("임시 표 적재 실패 (SQLState " + e.getSQLState() + ")");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        try {
            long inserted = jdbc.update(INSERT);
            return new CustomerAttributesLoadResult(true, fileRows, inserted);
        } catch (DataAccessException e) {
            // 22xxx(날짜 등 형식)·23xxx(필수값·위험등급 CHECK·길이)는 파일 문제 — 행 값이 담긴 원인 메시지는 붙이지 않는다.
            if (isDataError(e)) {
                throw new CustomerAttributesFileFormatException("값 형식이 맞지 않는 행이 있습니다");
            }
            throw e;
        }
    }

    private static boolean isDataError(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        if (!(cause instanceof SQLException sql) || sql.getSQLState() == null) {
            return false;
        }
        return sql.getSQLState().startsWith("22") || sql.getSQLState().startsWith("23");
    }
}
