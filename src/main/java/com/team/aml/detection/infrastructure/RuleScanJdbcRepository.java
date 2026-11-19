package com.team.aml.detection.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.detection.domain.ActiveRule;
import com.team.aml.detection.domain.CyclePath;
import com.team.aml.detection.domain.CycleRule;
import com.team.aml.detection.domain.DataRange;
import com.team.aml.detection.domain.FanEvidence;
import com.team.aml.detection.domain.FanInRule;
import com.team.aml.detection.domain.FanMinute;
import com.team.aml.detection.domain.FanOutRule;
import com.team.aml.detection.domain.FxRelayEvidence;
import com.team.aml.detection.domain.FxRelayMinute;
import com.team.aml.detection.domain.FxRelayRule;
import com.team.aml.detection.domain.GatherScatterMinute;
import com.team.aml.detection.domain.GatherScatterRule;
import com.team.aml.detection.domain.NewAlert;
import com.team.aml.detection.domain.RelayPair;
import com.team.aml.detection.domain.RuleScanRepository;
import com.team.aml.detection.domain.ScatterGatherLeg;
import com.team.aml.detection.domain.ScatterGatherRule;
import com.team.aml.shared.domain.DisplayNumber;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

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
     * ① 후보: 하루의 모든 창을 덮는 [D 00:01 - H, D+1 00:00) 에서 상대 계좌가 N 이상이고 그날 거래가 있는 계좌.
     *    그중 그날 모든 분의 1분 전 창에 공통으로 들어가는 [D+1 00:00 - 1분 - H, D 00:00) 에서 이미 N 이상인 계좌는 뺀다
     *    — 그날 어느 분에도 1분 전 창이 N 미만일 수 없어 거짓→참이 안 된다. 결과는 같고, 상대가 수천인 대형 계좌를
     *    분마다 다시 세지 않는다(9/5 R01: 6분 → 5초, 경보 252건 동일). H < 24시간+1분이면 이 구간이 비어 거르지 않는다.
     * ② 그 계좌의 그날 거래 분 E 마다 지금 창 [E+1분-H, E+1분) 과 1분 전 창 [E-H, E) 의 상대 계좌 수. H = window_hours.
     * 자기 계좌 거래는 셋 다 뺀다. %1$s = 대상 계좌 칼럼, %2$s = 상대 계좌 칼럼 (코드 상수만 넣는다).
     * R02 = (to_account_key, from_account_key) → transactions_to_ts_idx,
     * R01 = (from_account_key, to_account_key) → transactions_from_ts_idx.
     */
    private static final String FAN_MINUTES = """
            with candidates as (
                select %1$s as account_key
                from ops.transactions
                where ts >= ? and ts < ? and not is_self_transfer
                group by %1$s
                having count(distinct %2$s) >= ? and max(ts) >= ?
                   and count(distinct %2$s) filter (where ts >= ? and ts < ?) < ?
            ),
            minutes as (
                select distinct t.%1$s as account_key, date_trunc('minute', t.ts) as e
                from ops.transactions t
                join candidates c on c.account_key = t.%1$s
                where t.ts >= ? and t.ts < ? and not t.is_self_transfer
            )
            select m.account_key, m.e,
                   (select count(distinct x.%2$s) from ops.transactions x
                     where x.%1$s = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e + interval '1 minute' - make_interval(hours => ?)
                       and x.ts < m.e + interval '1 minute') as count_now,
                   (select count(distinct x.%2$s) from ops.transactions x
                     where x.%1$s = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e - make_interval(hours => ?) and x.ts < m.e) as count_before
            from minutes m
            order by m.account_key, m.e""";
    private static final String FAN_IN_MINUTES = FAN_MINUTES.formatted("to_account_key", "from_account_key");
    private static final String FAN_OUT_MINUTES = FAN_MINUTES.formatted("from_account_key", "to_account_key");

    /** %1$s = 대상 계좌 칼럼, %2$s·%3$s = 그 방향의 통화·금액 칼럼 (rules.md 1절 4 금액은 방향대로). */
    private static final String FAN_EVIDENCE = """
            select %2$s as currency, sum(%3$s) as total_amount, count(*) as transaction_count,
                   array_agg(transaction_id order by transaction_id) as transaction_ids
            from ops.transactions
            where %1$s = ? and not is_self_transfer and ts >= ? and ts < ?
            group by %2$s
            order by %2$s""";
    private static final String FAN_IN_EVIDENCE =
            FAN_EVIDENCE.formatted("to_account_key", "currency_received", "amount_received");
    private static final String FAN_OUT_EVIDENCE =
            FAN_EVIDENCE.formatted("from_account_key", "currency_paid", "amount_paid");

    /*
     * R06 ① 후보: [D 00:01 - H, D+1 00:00) 에서 송신 계좌 ≥ 하한·수신 계좌 ≥ 하한이고 그날 입금·출금이 있는 계좌.
     *    그중 그날 모든 분의 지금 창에 공통으로 들어가는 [D+1 00:00 - H, D 00:01) 에서 이미 상한을 넘는 계좌는 뺀다
     *    — 그날 어느 분에도 지금 창이 참일 수 없다(결과는 같고 대형 계좌를 분마다 세지 않음). H < 24시간이면 구간이 비어 거르지 않는다.
     * ② 그 계좌의 그날 거래 분 E(입금·출금) 마다 지금 창·1분 전 창의 송신·수신 계좌 수. 자기 계좌 거래는 모두 뺀다.
     *    송신 수는 transactions_to_ts_idx, 수신 수는 transactions_from_ts_idx 를 탄다.
     */
    private static final String GATHER_SCATTER_MINUTES = """
            with legs as (
                select to_account_key as account_key, from_account_key as counterparty, true as inbound, ts
                from ops.transactions where ts >= ? and ts < ? and not is_self_transfer
                union all
                select from_account_key, to_account_key, false, ts
                from ops.transactions where ts >= ? and ts < ? and not is_self_transfer
            ),
            candidates as (
                select account_key
                from legs
                group by account_key
                having count(distinct counterparty) filter (where inbound) >= ?
                   and count(distinct counterparty) filter (where not inbound) >= ?
                   and max(ts) >= ?
                   and count(distinct counterparty) filter (where inbound and ts >= ? and ts < ?) <= ?
                   and count(distinct counterparty) filter (where not inbound and ts >= ? and ts < ?) <= ?
            ),
            minutes as (
                select distinct l.account_key, date_trunc('minute', l.ts) as e
                from legs l join candidates c on c.account_key = l.account_key
                where l.ts >= ?
            )
            select m.account_key, m.e,
                   (select count(distinct x.from_account_key) from ops.transactions x
                     where x.to_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e + interval '1 minute' - make_interval(hours => ?)
                       and x.ts < m.e + interval '1 minute') as senders_now,
                   (select count(distinct x.to_account_key) from ops.transactions x
                     where x.from_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e + interval '1 minute' - make_interval(hours => ?)
                       and x.ts < m.e + interval '1 minute') as receivers_now,
                   (select count(distinct x.from_account_key) from ops.transactions x
                     where x.to_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e - make_interval(hours => ?) and x.ts < m.e) as senders_before,
                   (select count(distinct x.to_account_key) from ops.transactions x
                     where x.from_account_key = m.account_key and not x.is_self_transfer
                       and x.ts >= m.e - make_interval(hours => ?) and x.ts < m.e) as receivers_before
            from minutes m
            order by m.account_key, m.e""";

    /** 환율표 jsonb → (currency, rate). 표에 없는 통화는 join 에서 빠져 평가하지 않는다. */
    private static final String RATES =
            "rates as (select key as currency, value::numeric as rate from jsonb_each_text(?::jsonb))";

    /** R03 쌍 조건 — b 금액 × rate[a] 가 a 금액 × rate[b] × (1 ± tolerance) 안(양 끝 포함). 나눗셈 없이 곱셈으로. */
    private static final String RELAY_COND = """
            a.amount_received > 0
                  and (not ? or a.currency_received <> b.currency_paid)
                  and b.amount_paid * ra.rate between (1 - ?) * a.amount_received * rb.rate
                                                  and (1 + ?) * a.amount_received * rb.rate""";

    /*
     * R03 ① 그날 출금 b 마다 그 계좌가 창 [b분+1분-H, b분) 에 받은 거래 a 중 쌍이 되는 게 하나라도 있는지(EXISTS) → 쌍을 완성하는 (계좌, 분 E).
     *    쌍 전부는 여기서 만들지 않는다 — 입금·출금이 많은 계좌에서 조합이 불어나지 않게. 쌍 전부는 경보를 만들 때 근거 조회(FX_RELAY_EVIDENCE)에서만.
     *    a.ts < b분 = "a 가 b 보다 이른 분"(원천 시각이 분 단위). 받은 쪽은 transactions_to_ts_idx 를 탄다.
     * ② 그 (계좌, E) 의 1분 전 창 [E-H, E) 에도 쌍(b < E, a ≥ E-H)이 있었는지.
     */
    private static final String FX_RELAY_MINUTES = """
            with %1$s,
            pairs_now as (
                select distinct b.from_account_key as account_key, date_trunc('minute', b.ts) as e
                from ops.transactions b
                join rates rb on rb.currency = b.currency_paid
                where b.ts >= ? and b.ts < ? and not b.is_self_transfer
                  and exists (
                      select 1
                      from ops.transactions a
                      join rates ra on ra.currency = a.currency_received
                      where a.to_account_key = b.from_account_key and not a.is_self_transfer
                        and a.ts >= date_trunc('minute', b.ts) + interval '1 minute' - make_interval(hours => ?)
                        and a.ts < date_trunc('minute', b.ts)
                        and %2$s
                  )
            )
            select p.account_key, p.e,
                   exists (
                       select 1
                       from ops.transactions b
                       join rates rb on rb.currency = b.currency_paid
                       join ops.transactions a on a.to_account_key = b.from_account_key and not a.is_self_transfer
                            and a.ts >= p.e - make_interval(hours => ?) and a.ts < date_trunc('minute', b.ts)
                       join rates ra on ra.currency = a.currency_received
                       where b.from_account_key = p.account_key and not b.is_self_transfer
                         and b.ts >= p.e - make_interval(hours => ?) and b.ts < p.e
                         and %2$s
                   ) as pair_before
            from pairs_now p
            order by p.account_key, p.e""".formatted(RATES, RELAY_COND);

    /** R03 근거 — 창 안 모든 쌍, 받은 거래 시각순(rules.md 2.1 경로끼리 첫 거래 시각순). */
    private static final String FX_RELAY_EVIDENCE = """
            with %1$s
            select a.transaction_id as in_id, a.ts as in_ts, a.to_account_key as in_account,
                   a.currency_received as in_currency, a.amount_received as in_amount,
                   b.transaction_id as out_id, b.ts as out_ts, b.from_account_key as out_account,
                   b.currency_paid as out_currency, b.amount_paid as out_amount
            from ops.transactions b
            join rates rb on rb.currency = b.currency_paid
            join ops.transactions a on a.to_account_key = b.from_account_key and not a.is_self_transfer
                 and a.ts >= ? and a.ts < date_trunc('minute', b.ts)
            join rates ra on ra.currency = a.currency_received
            where b.from_account_key = ? and not b.is_self_transfer and b.ts >= ? and b.ts < ?
              and %2$s
            order by a.ts, a.transaction_id, b.ts, b.transaction_id""".formatted(RATES, RELAY_COND);

    /*
     * R05 ① 엣지: 1분 전 창까지 덮는 [D - H, D+1) 의 (보낸, 받은) 계좌 쌍 + 처음·마지막 거래 시각.
     * ② 후보 (S, T): 그날 받은 거래가 있는 T 마다 S→M·M→T 엣지가 있고 S→M 첫 거래 분 < M→T 마지막 거래 분인
     *    서로 다른 M 이 N 이상, S ≠ T (M 은 자기 계좌 거래가 빠진 엣지라 S·T 와 다르다).
     *    조인 전에 송금 상대가 N 미만인 S·입금 상대가 N 미만인 T 는 뺀다 — 그런 계좌는 서로 다른 M 이 N 개일 수 없어 결과가 같다.
     *    거래가 몰린 M 에서 S×T 조합이 불어나지 않게(9/5: 중간 행 903만 → 10만, 후보 찾기 21초 → 0.7초, #리뷰).
     * ③ 후보의 M 마다 S→M·M→T 거래를 그대로 가져온다(판정·대표 경로는 ScatterGatherRule).
     * 9/5 측정: 엣지 55만, 후보 (S, T) 39쌍.
     */
    private static final String SCATTER_GATHER_LEGS = """
            with e as (
                select from_account_key as src, to_account_key as dst, min(ts) as first_ts, max(ts) as last_ts
                from ops.transactions
                where ts >= ? and ts < ? and not is_self_transfer
                group by from_account_key, to_account_key
            ),
            srcs as (select src from e group by src having count(*) >= ?),
            targets as (select dst as t from e group by dst having count(*) >= ? and max(last_ts) >= ?),
            mids as (
                select e1.src as s, e2.dst as t, e2.src as m
                from targets tg
                join e e2 on e2.dst = tg.t
                join e e1 on e1.dst = e2.src
                join srcs on srcs.src = e1.src
                where e1.src <> e2.dst and e1.first_ts < date_trunc('minute', e2.last_ts)
            ),
            cand as (select s, t from mids group by s, t having count(distinct m) >= ?)
            select m.s, m.t, true as to_target, x.transaction_id, x.ts, x.from_account_key, x.to_account_key
            from mids m
            join cand c on c.s = m.s and c.t = m.t
            join ops.transactions x on x.from_account_key = m.m and x.to_account_key = m.t and not x.is_self_transfer
                 and x.ts >= ? and x.ts < ?
            union all
            select m.s, m.t, false, x.transaction_id, x.ts, x.from_account_key, x.to_account_key
            from mids m
            join cand c on c.s = m.s and c.t = m.t
            join ops.transactions x on x.from_account_key = m.s and x.to_account_key = m.m and not x.is_self_transfer
                 and x.ts >= ? and x.ts < ?
            order by 1, 2, 5, 4""";

    /*
     * R04 ① 엣지: [closeFrom + 1분 - H, closeTo) 의 (보낸 계좌, 받은 계좌) 쌍 + 처음·마지막 거래 시각.
     * ② 계좌 고리 후보: 돌아오는 엣지 z→a(그 구간에 거래)에서 출발, a→b 를 붙여 b=z(2홉)·b→z(3홉)·b→c→z(4홉).
     *    앞 엣지의 첫 거래 분 < 다음 엣지의 마지막 거래 분인 것만 (시간순 가능성). 중간 계좌는 서로 다르고 a 가 아님.
     * ③ 후보만 거래 단위로 펼친다: 돌아오는 거래 tk ∈ [closeFrom, closeTo), 앞 거래마다 창 시작(tk분+1분-H) 이상·다음 거래 분보다 이른 분.
     * 9/5 측정(rules.md R04 구현 1단계 기준선): 엣지 55만, 후보 2홉 425·3홉 26·4홉 7, 4홉 후보 조인이 약 21초.
     */
    private static final String CYCLES = """
            with e as (
                select from_account_key as src, to_account_key as dst, min(ts) as first_ts, max(ts) as last_ts
                from ops.transactions
                where ts >= ? and ts < ? and not is_self_transfer
                group by from_account_key, to_account_key
            ),
            closing as (
                select src as z, dst as a, last_ts as close_ts from e where last_ts >= ?
            ),
            start as (
                select c.a, e1.dst as b, c.z, e1.first_ts as f1, c.close_ts
                from closing c join e e1 on e1.src = c.a
                where e1.first_ts < date_trunc('minute', c.close_ts)
            ),
            cand as (
                select a, b, null::text as c, z, 2 as k from start where b = z
                union all
                select s.a, s.b, null, s.z, 3
                from start s join e e2 on e2.src = s.b and e2.dst = s.z
                where ? >= 3 and s.b <> s.z
                  and s.f1 < date_trunc('minute', e2.last_ts) and e2.first_ts < date_trunc('minute', s.close_ts)
                union all
                select s.a, s.b, e2.dst, s.z, 4
                from start s
                join e e2 on e2.src = s.b and e2.dst not in (s.a, s.b, s.z)
                join e e3 on e3.src = e2.dst and e3.dst = s.z
                where ? >= 4 and s.b <> s.z
                  and s.f1 < date_trunc('minute', e2.last_ts) and e2.first_ts < date_trunc('minute', e3.last_ts)
                  and e3.first_ts < date_trunc('minute', s.close_ts)
            ),
            closes as (
                select tk.transaction_id, tk.ts, tk.from_account_key, tk.to_account_key,
                       date_trunc('minute', tk.ts) + interval '1 minute' - make_interval(hours => ?) as ws
                from ops.transactions tk
                join (select distinct a, z from cand) p on tk.from_account_key = p.z and tk.to_account_key = p.a
                where tk.ts >= ? and tk.ts < ? and not tk.is_self_transfer
            )
            select array[t1.transaction_id, tk.transaction_id] as ids, array[t1.ts, tk.ts] as tss,
                   array[t1.from_account_key, tk.from_account_key] as froms,
                   array[t1.to_account_key, tk.to_account_key] as tos
            from cand c
            join closes tk on tk.from_account_key = c.z and tk.to_account_key = c.a
            join ops.transactions t1 on t1.from_account_key = c.a and t1.to_account_key = c.b and not t1.is_self_transfer
                 and t1.ts >= tk.ws and t1.ts < date_trunc('minute', tk.ts)
            where c.k = 2
            union all
            select array[t1.transaction_id, t2.transaction_id, tk.transaction_id], array[t1.ts, t2.ts, tk.ts],
                   array[t1.from_account_key, t2.from_account_key, tk.from_account_key],
                   array[t1.to_account_key, t2.to_account_key, tk.to_account_key]
            from cand c
            join closes tk on tk.from_account_key = c.z and tk.to_account_key = c.a
            join ops.transactions t2 on t2.from_account_key = c.b and t2.to_account_key = c.z and not t2.is_self_transfer
                 and t2.ts >= tk.ws and t2.ts < date_trunc('minute', tk.ts)
            join ops.transactions t1 on t1.from_account_key = c.a and t1.to_account_key = c.b and not t1.is_self_transfer
                 and t1.ts >= tk.ws and t1.ts < date_trunc('minute', t2.ts)
            where c.k = 3
            union all
            select array[t1.transaction_id, t2.transaction_id, t3.transaction_id, tk.transaction_id],
                   array[t1.ts, t2.ts, t3.ts, tk.ts],
                   array[t1.from_account_key, t2.from_account_key, t3.from_account_key, tk.from_account_key],
                   array[t1.to_account_key, t2.to_account_key, t3.to_account_key, tk.to_account_key]
            from cand c
            join closes tk on tk.from_account_key = c.z and tk.to_account_key = c.a
            join ops.transactions t3 on t3.from_account_key = c.c and t3.to_account_key = c.z and not t3.is_self_transfer
                 and t3.ts >= tk.ws and t3.ts < date_trunc('minute', tk.ts)
            join ops.transactions t2 on t2.from_account_key = c.b and t2.to_account_key = c.c and not t2.is_self_transfer
                 and t2.ts >= tk.ws and t2.ts < date_trunc('minute', t3.ts)
            join ops.transactions t1 on t1.from_account_key = c.a and t1.to_account_key = c.b and not t1.is_self_transfer
                 and t1.ts >= tk.ws and t1.ts < date_trunc('minute', t2.ts)
            where c.k = 4""";
    /** 같은 (대상 계좌, 닫힌 분) 고리가 이어서 오게 — 묶음이 끝날 때마다 저장한다. 묶음 안은 첫 거래 시각·거래 id 순. */
    private static final String CYCLES_GROUPED = "select * from (" + CYCLES + ") q "
            + "order by q.froms[1], date_trunc('minute', q.tss[array_length(q.tss, 1)]), q.tss[1], q.ids";
    /** 커서로 한 번에 받는 행 수. 트랜잭션 안(autocommit off)이라 PostgreSQL JDBC 가 이만큼씩 나눠 받는다. */
    private static final int CYCLE_FETCH_SIZE = 1000;

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
    public List<FanMinute> fanInMinutes(LocalDate date, FanInRule rule) {
        return fanMinutes(FAN_IN_MINUTES, date, rule.windowHours(), rule.minDistinctSenders());
    }

    @Override
    public List<FanMinute> fanOutMinutes(LocalDate date, FanOutRule rule) {
        return fanMinutes(FAN_OUT_MINUTES, date, rule.windowHours(), rule.minDistinctReceivers());
    }

    private List<FanMinute> fanMinutes(String sql, LocalDate date, int windowHours, int minCount) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime candidatesFrom = dayStart.plusMinutes(1).minusHours(windowHours);   // 그날 첫 분(00:00)의 창 시작
        LocalDateTime alwaysBeforeFrom = dayEnd.minusMinutes(1).minusHours(windowHours);  // 마지막 분(23:59)의 1분 전 창 시작
        return jdbc.query(sql, (rs, rowNum) -> new FanMinute(rs.getString("account_key"),
                        rs.getObject("e", LocalDateTime.class), rs.getInt("count_now"), rs.getInt("count_before")),
                candidatesFrom, dayEnd, minCount, dayStart, alwaysBeforeFrom, dayStart, minCount,
                dayStart, dayEnd, windowHours, windowHours);
    }

    @Override
    public List<GatherScatterMinute> gatherScatterMinutes(LocalDate date, GatherScatterRule rule) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        int h = rule.windowHours();
        LocalDateTime candidatesFrom = dayStart.plusMinutes(1).minusHours(h);   // 첫 분(00:00)의 지금 창 시작
        LocalDateTime alwaysNowFrom = dayEnd.minusHours(h);                     // 마지막 분(23:59)의 지금 창 시작
        LocalDateTime alwaysNowTo = dayStart.plusMinutes(1);                    // 첫 분(00:00)의 지금 창 끝
        return jdbc.query(GATHER_SCATTER_MINUTES, (rs, rowNum) -> new GatherScatterMinute(rs.getString("account_key"),
                        rs.getObject("e", LocalDateTime.class), rs.getInt("senders_now"), rs.getInt("receivers_now"),
                        rs.getInt("senders_before"), rs.getInt("receivers_before")),
                candidatesFrom, dayEnd, candidatesFrom, dayEnd,
                rule.minSenders(), rule.minReceivers(), dayStart,
                alwaysNowFrom, alwaysNowTo, rule.maxSenders(),
                alwaysNowFrom, alwaysNowTo, rule.maxReceivers(),
                dayStart, h, h, h, h);
    }

    @Override
    public FanEvidence fanInEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return fanEvidence(FAN_IN_EVIDENCE, accountKey, windowStart, windowEnd);
    }

    @Override
    public FanEvidence fanOutEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return fanEvidence(FAN_OUT_EVIDENCE, accountKey, windowStart, windowEnd);
    }

    private FanEvidence fanEvidence(String sql, String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<FanEvidence.CurrencyTotal> byCurrency = jdbc.query(sql, (rs, rowNum) ->
                new FanEvidence.CurrencyTotal(rs.getString("currency"), rs.getBigDecimal("total_amount"),
                        rs.getInt("transaction_count"), ids(rs.getArray("transaction_ids"))),
                accountKey, windowStart, windowEnd);
        return new FanEvidence(entityIdOf(accountKey), byCurrency);
    }

    @Override
    public List<FxRelayMinute> fxRelayMinutes(LocalDate date, FxRelayRule rule) {
        LocalDateTime dayStart = date.atStartOfDay();
        int h = rule.windowHours();
        BigDecimal tol = rule.amountTolerance();
        boolean change = rule.requireCurrencyChange();
        return jdbc.query(FX_RELAY_MINUTES, (rs, rowNum) -> new FxRelayMinute(rs.getString("account_key"),
                        rs.getObject("e", LocalDateTime.class), rs.getBoolean("pair_before")),
                ratesJson(rule), dayStart, dayStart.plusDays(1), h, change, tol, tol,
                h, h, change, tol, tol);
    }

    @Override
    public FxRelayEvidence fxRelayEvidence(String accountKey, LocalDateTime windowStart, LocalDateTime windowEnd,
                                           FxRelayRule rule) {
        BigDecimal tol = rule.amountTolerance();
        List<RelayPair> pairs = jdbc.query(FX_RELAY_EVIDENCE, (rs, rowNum) -> new RelayPair(
                        rs.getLong("in_id"), rs.getObject("in_ts", LocalDateTime.class), rs.getString("in_account"),
                        rs.getString("in_currency"), rs.getBigDecimal("in_amount"),
                        rs.getLong("out_id"), rs.getObject("out_ts", LocalDateTime.class), rs.getString("out_account"),
                        rs.getString("out_currency"), rs.getBigDecimal("out_amount")),
                ratesJson(rule), windowStart, accountKey, windowStart, windowEnd,
                rule.requireCurrencyChange(), tol, tol);
        return new FxRelayEvidence(entityIdOf(accountKey), accountKey, windowStart, windowEnd, pairs);
    }

    /** 환율표를 jsonb 로 — 숫자는 BigDecimal 그대로 (jsonb 가 numeric 으로 받는다). */
    private String ratesJson(FxRelayRule rule) {
        ObjectNode n = om.createObjectNode();
        rule.usdRates().forEach(n::put);
        return text(n);
    }

    @Override
    public List<ScatterGatherLeg> scatterGatherLegs(LocalDate date, ScatterGatherRule rule) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime from = dayStart.minusHours(rule.windowHours());
        LocalDateTime dayEnd = dayStart.plusDays(1);
        return jdbc.query(SCATTER_GATHER_LEGS, (rs, rowNum) -> new ScatterGatherLeg(rs.getString(1), rs.getString(2),
                        rs.getBoolean(3), rs.getLong(4), rs.getObject(5, LocalDateTime.class),
                        rs.getString(6), rs.getString(7)),
                from, dayEnd, rule.minIntermediates(), rule.minIntermediates(), dayStart, rule.minIntermediates(),
                from, dayEnd, from, dayEnd);
    }

    @Override
    public void forEachCycleGroup(LocalDateTime closeFrom, LocalDateTime closeTo, CycleRule rule,
                                  Consumer<List<CyclePath>> group) {
        int h = rule.windowHours();
        Object[] args = {closeFrom.plusMinutes(1).minusHours(h), closeTo, closeFrom,
                rule.maxHops(), rule.maxHops(), h, closeFrom, closeTo};
        List<CyclePath> current = new ArrayList<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(CYCLES_GROUPED);
            ps.setFetchSize(CYCLE_FETCH_SIZE);
            new ArgumentPreparedStatementSetter(args).setValues(ps);
            return ps;
        }, (RowCallbackHandler) rs -> {
            CyclePath p = cyclePath(rs);
            if (!current.isEmpty() && !(current.get(0).accountKey().equals(p.accountKey())
                    && current.get(0).closeMinute().equals(p.closeMinute()))) {
                group.accept(List.copyOf(current));
                current.clear();
            }
            current.add(p);
        });
        if (!current.isEmpty()) {
            group.accept(List.copyOf(current));
        }
    }

    private static CyclePath cyclePath(ResultSet rs) throws SQLException {
        return new CyclePath(
                List.of((Long[]) rs.getArray("ids").getArray()),
                Arrays.stream((Timestamp[]) rs.getArray("tss").getArray()).map(Timestamp::toLocalDateTime).toList(),
                List.of((String[]) rs.getArray("froms").getArray()),
                List.of((String[]) rs.getArray("tos").getArray()));
    }

    @Override
    public String entityIdOf(String accountKey) {
        return jdbc.query("select entity_id from ops.accounts where account_key = ?",
                (rs, rowNum) -> rs.getString(1), accountKey).stream().findFirst().orElse(null);
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

    /** 룰 기준값은 소수를 BigDecimal 로 읽는다 — double 로 읽으면 17자리 넘는 소수(경계값)가 잘린다(#리뷰). */
    private JsonNode json(String text) {
        try {
            return om.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
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
