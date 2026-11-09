package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.ActiveRule;
import com.team.aml.detection.domain.DataRange;
import com.team.aml.detection.domain.FanInEvidence;
import com.team.aml.detection.domain.FanInMinute;
import com.team.aml.detection.domain.FanInRule;
import com.team.aml.detection.domain.NewAlert;
import com.team.aml.detection.domain.RuleScanRepository;
import com.team.aml.shared.domain.DisplayNumber;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class RuleScanJdbcRepository implements RuleScanRepository {

    /** 룰 스캔 시작을 줄 세우는 advisory lock 키 (적재는 4_004_001). */
    private static final long SCAN_START_LOCK = 5_005_001L;

    private static final String DATA_RANGE =
            "select min(ts)::date as first_date, max(ts)::date as last_date from ops.transactions";

    private static final String ACTIVE_RULES = """
            select id, rule_id, version, typology_hint, params::text as params
            from ops.rules where is_active order by rule_id""";

    /*
     * ① 후보: 하루의 모든 창을 덮는 [D 00:01 - H, D+1 00:00) 에서 송금 계좌가 N 이상이고 그날 입금이 있는 계좌.
     * ② 그 계좌의 그날 입금 분 E 마다 지금 창 [E+1분-H, E+1분) 과 1분 전 창 [E-H, E) 의 송금 계좌 수. H = window_hours.
     * 자기 계좌 거래는 셋 다 뺀다. transactions_to_ts_idx (to_account_key, ts) 를 탄다.
     */
    private static final String FAN_IN_MINUTES = """
            with candidates as (
                select to_account_key as account_key
                from ops.transactions
                where ts >= ? and ts < ? and not is_self_transfer
                group by to_account_key
                having count(distinct from_account_key) >= ? and max(ts) >= ?
            ),
            minutes as (
                select distinct t.to_account_key as account_key, date_trunc('minute', t.ts) as e
                from ops.transactions t
                join candidates c on c.account_key = t.to_account_key
                where t.ts >= ? and t.ts < ? and not t.is_self_transfer
            )
            select m.account_key, m.e,
                   (select count(distinct x.from_account_key) from ops.transactions x
                     where x.to_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e + interval '1 minute' - make_interval(hours => ?)
                       and x.ts < m.e + interval '1 minute') as senders_now,
                   (select count(distinct x.from_account_key) from ops.transactions x
                     where x.to_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e - make_interval(hours => ?) and x.ts < m.e) as senders_before
            from minutes m
            order by m.account_key, m.e""";

    private static final String FAN_IN_EVIDENCE = """
            select currency_received as currency, sum(amount_received) as total_amount, count(*) as transaction_count,
                   array_agg(transaction_id order by transaction_id) as transaction_ids
            from ops.transactions
            where to_account_key = ? and not is_self_transfer and ts >= ? and ts < ?
            group by currency_received
            order by currency_received""";

    private static final String INSERT_ALERT = """
            insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                   window_end, reference_ts, rule_facts, batch_date)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public RuleScanJdbcRepository(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void lockScanStart() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, SCAN_START_LOCK);
    }

    @Override
    public Optional<DataRange> dataRange() {
        return jdbc.query(DATA_RANGE, (rs, rowNum) -> {
            LocalDate first = rs.getObject("first_date", LocalDate.class);
            return first == null ? null : new DataRange(first, rs.getObject("last_date", LocalDate.class));
        }).stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public List<ActiveRule> findActiveRules() {
        return jdbc.query(ACTIVE_RULES, (rs, rowNum) -> new ActiveRule(rs.getLong("id"), rs.getString("rule_id"),
                rs.getInt("version"), rs.getString("typology_hint"), json(rs.getString("params"))));
    }

    @Override
    public int countTransactionsOn(LocalDate date) {
        Integer n = jdbc.queryForObject("select count(*) from ops.transactions where ts >= ? and ts < ?",
                Integer.class, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        return n == null ? 0 : n;
    }

    @Override
    public List<FanInMinute> fanInMinutes(LocalDate date, FanInRule rule) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime candidatesFrom = rule.windowStart(dayStart);   // 그날 첫 분(00:00)의 창 시작
        return jdbc.query(FAN_IN_MINUTES, (rs, rowNum) -> new FanInMinute(rs.getString("account_key"),
                        rs.getObject("e", LocalDateTime.class), rs.getInt("senders_now"), rs.getInt("senders_before")),
                candidatesFrom, dayEnd, rule.minDistinctSenders(), dayStart, dayStart, dayEnd,
                rule.windowHours(), rule.windowHours());
    }

    @Override
    public FanInEvidence fanInEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<FanInEvidence.CurrencyTotal> byCurrency = jdbc.query(FAN_IN_EVIDENCE, (rs, rowNum) ->
                new FanInEvidence.CurrencyTotal(rs.getString("currency"), rs.getBigDecimal("total_amount"),
                        rs.getInt("transaction_count"), ids(rs.getArray("transaction_ids"))),
                accountKey, windowStart, windowEnd);
        String entityId = jdbc.query("select entity_id from ops.accounts where account_key = ?",
                (rs, rowNum) -> rs.getString(1), accountKey).stream().findFirst().orElse(null);
        return new FanInEvidence(entityId, byCurrency);
    }

    @Override
    public long insertAlert(NewAlert a) {
        Long seq = jdbc.queryForObject("select nextval('ops.alert_no_seq')", Long.class);
        Long id = jdbc.queryForObject(INSERT_ALERT, Long.class, DisplayNumber.alertNo(a.batchDate(), seq),
                a.batchRunId(), a.ruleRefId(), a.accountKey(), a.entityId(), a.windowStart(), a.windowEnd(),
                a.referenceTs(), text(a.ruleFacts()), a.batchDate());
        jdbc.batchUpdate("insert into ops.alert_transactions(alert_id, transaction_id) values (?, ?)",
                a.transactionIds(), 500, (ps, txId) -> {
                    ps.setLong(1, id);
                    ps.setLong(2, txId);
                });
        return id;
    }

    @Override
    public int failRunningScans(String message) {
        return jdbc.update("""
                update ops.batch_runs set status = 'FAILED', error_message = ?, finished_at = now()
                where batch_type = 'RULE_SCAN' and status = 'RUNNING'""", message);
    }

    private static List<Long> ids(Array array) throws SQLException {
        return Arrays.asList((Long[]) array.getArray());
    }

    private JsonNode json(String text) {
        try {
            return om.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 룰 기준값을 읽을 수 없습니다", e);
        }
    }

    private String text(JsonNode node) {
        try {
            return om.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("rule_facts 를 JSON 으로 만들 수 없습니다", e);
        }
    }
}
