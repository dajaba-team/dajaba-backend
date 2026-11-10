package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.DatasetNotLoadedException;
import com.team.aml.detection.domain.DateOutOfRangeException;
import com.team.aml.detection.domain.InvalidRuleParamsException;
import com.team.aml.detection.domain.NoActiveRulesException;
import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 룰 스캔 R02 (SCRUM-99). 시드 V011 의 R02_FAN_IN 버전 1(96시간, N=5)로 돈다.
 * 경보는 지울 수 없고 같은 날짜는 한 번만 성공하므로 테스트마다 날짜·계좌를 따로 쓰고, 단언은 그 계좌의 경보만 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunRuleScanTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SYSTEM = CurrentUser.SYSTEM_ID;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @Autowired
    RunRuleScan runRuleScan;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper om;

    private static Long sourceFileId;

    @Test
    @Order(1)   // 거래를 넣기 전에
    void 거래가_없으면_409_DATASET_NOT_LOADED이고_배치를_남기지_않는다() {
        assertThatThrownBy(() -> runRuleScan.start(LocalDate.of(2022, 9, 2), SYSTEM))
                .isInstanceOf(DatasetNotLoadedException.class);
        assertThat(count("select count(*) from ops.batch_runs where batch_type = 'RULE_SCAN'")).isZero();
    }

    @Test
    void 송금_계좌가_N개가_되는_분에_경보_1건() {
        LocalDate d = LocalDate.of(2022, 9, 2);
        String target = accountWithEntity();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(inbound(target, newKey(), d.atTime(10, i), "100.5", "US Dollar"));
        }
        inbound(target, newKey(), d.atTime(10, 30), "7", "Euro");   // 참이 이어지는 중 — 새 경보 없음

        RuleScanResult result = runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> alerts = alertsOf(target);
        assertThat(alerts).hasSize(1);
        Map<String, Object> a = alerts.get(0);
        assertThat(ts(a.get("window_start"))).isEqualTo(d.minusDays(4).atTime(10, 5));
        assertThat(ts(a.get("window_end"))).isEqualTo(d.atTime(10, 5));
        assertThat(ts(a.get("reference_ts"))).isEqualTo(d.atTime(10, 5));
        assertThat(a.get("batch_date")).isEqualTo(Date.valueOf(d));
        assertThat(a.get("entity_id")).isEqualTo("E_" + target);
        assertThat(a.get("score_status")).isEqualTo("PENDING");
        assertThat((String) a.get("alert_no")).startsWith("ALT-20220902-");
        assertThat(linkedIds((Long) a.get("id"))).containsExactlyElementsOf(ids);

        JsonNode facts = facts(a);
        assertThat(facts.get("distinct_senders").asInt()).isEqualTo(5);
        assertThat(facts.get("amounts_by_currency")).hasSize(1);
        JsonNode usd = facts.get("amounts_by_currency").get(0);
        assertThat(usd.get("currency").asText()).isEqualTo("US Dollar");
        assertThat(usd.get("total_amount").decimalValue()).isEqualByComparingTo("502.5");
        assertThat(usd.get("transaction_count").asInt()).isEqualTo(5);

        assertThat(result.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
        assertThat(result.run().rowsIn()).isEqualTo(count(
                "select count(*) from ops.transactions where ts >= '2022-09-02' and ts < '2022-09-03'"));
        assertThat(result.alertsByRule()).singleElement().satisfies(r -> {
            assertThat(r.ruleId()).isEqualTo("R02_FAN_IN");
            assertThat(r.ruleVersion()).isEqualTo(1);
        });
    }

    @Test
    void 같은_분의_거래는_함께_평가한다() {
        LocalDate d = LocalDate.of(2022, 9, 3);
        String target = newKey();
        for (int i = 0; i < 5; i++) {
            inbound(target, newKey(), d.atTime(12, 0), "1", "US Dollar");
        }

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(target)).singleElement()
                .satisfies(a -> assertThat(ts(a.get("reference_ts"))).isEqualTo(d.atTime(12, 1)));
    }

    @Test
    void 참이_다음_날까지_이어지면_새_경보가_없고_창_밖으로_빠진_뒤_다시_참이면_새_경보() {
        LocalDate d1 = LocalDate.of(2022, 9, 20);   // 96시간 창이 다른 테스트 날짜와 안 겹치게
        LocalDate d2 = d1.plusDays(1);
        LocalDate d5 = d1.plusDays(4);
        String target = newKey();
        for (int i = 0; i < 5; i++) {
            inbound(target, newKey(), d1.atTime(23, 55 + i), "1", "US Dollar");   // d1 23:59 에 참 → 경보 ①
        }
        inbound(target, newKey(), d2.atTime(0, 30), "1", "US Dollar");            // 6명, 참 유지 → 없음
        for (int i = 0; i < 4; i++) {
            inbound(target, newKey(), d5.atTime(23, 59), "1", "US Dollar");       // d1 5명은 빠지고 d2 00:30 1명 + 4명 = 5 → 경보 ②
        }

        runRuleScan.run(runRuleScan.start(d1, SYSTEM), d1);
        runRuleScan.run(runRuleScan.start(d2, SYSTEM), d2);
        runRuleScan.run(runRuleScan.start(d5, SYSTEM), d5);

        assertThat(alertsOf(target)).extracting(a -> ts(a.get("reference_ts")))
                .containsExactly(d2.atStartOfDay(), d5.plusDays(1).atStartOfDay());
        assertThat(alertsOf(target)).extracting(a -> a.get("batch_date"))
                .containsExactly(Date.valueOf(d1), Date.valueOf(d5));
    }

    @Test
    void 자기_계좌_거래는_세지_않는다() {
        LocalDate d = LocalDate.of(2022, 9, 6);
        String target = newKey();
        for (int i = 0; i < 4; i++) {
            inbound(target, newKey(), d.atTime(9, i), "1", "US Dollar");
        }
        selfTransfer(target, d.atTime(9, 4));

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(target)).isEmpty();
    }

    @Test
    void 창_시작_시각은_포함하고_그_1분_전은_뺀다() {
        LocalDate d = LocalDate.of(2022, 9, 7);
        String included = newKey();
        String excluded = newKey();
        for (int i = 0; i < 4; i++) {
            inbound(included, newKey(), d.minusDays(4).atTime(10, 10), "1", "US Dollar");   // = E+1분-96시간
            inbound(excluded, newKey(), d.minusDays(4).atTime(10, 9), "1", "US Dollar");    // 정확히 96시간 전 그 분
        }
        inbound(included, newKey(), d.atTime(10, 9), "1", "US Dollar");
        inbound(excluded, newKey(), d.atTime(10, 9), "1", "US Dollar");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(included)).hasSize(1);
        assertThat(alertsOf(excluded)).isEmpty();
    }

    @Test
    void 데이터_기간_밖이면_409_DATE_OUT_OF_RANGE() {
        inbound(newKey(), newKey(), LocalDateTime.of(2022, 9, 8, 0, 0), "1", "US Dollar");
        assertThatThrownBy(() -> runRuleScan.start(LocalDate.of(2030, 1, 1), SYSTEM))
                .isInstanceOf(DateOutOfRangeException.class);
    }

    @Test
    void 같은_날짜가_실행_중이면_409_BATCH_RUNNING() {
        LocalDate d = LocalDate.of(2022, 9, 9);
        inbound(newKey(), newKey(), d.atTime(1, 0), "1", "US Dollar");
        jdbc.update("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', ?, 'RUNNING', 1)", d);

        assertThatThrownBy(() -> runRuleScan.start(d, SYSTEM)).isInstanceOf(BatchRunningException.class);
    }

    @Test
    void 이미_성공한_날짜는_409_BATCH_ALREADY_DONE() {
        LocalDate d = LocalDate.of(2022, 9, 10);
        inbound(newKey(), newKey(), d.atTime(1, 0), "1", "US Dollar");
        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThatThrownBy(() -> runRuleScan.start(d, SYSTEM)).isInstanceOf(BatchAlreadyDoneException.class);
    }

    @Test
    void 켜진_룰이_없으면_409_NO_ACTIVE_RULES() {
        LocalDate d = LocalDate.of(2022, 9, 11);
        inbound(newKey(), newKey(), d.atTime(1, 0), "1", "US Dollar");
        setActive("R02_FAN_IN", 1, false);
        try {
            assertThatThrownBy(() -> runRuleScan.start(d, SYSTEM)).isInstanceOf(NoActiveRulesException.class);
        } finally {
            setActive("R02_FAN_IN", 1, true);
        }
    }

    @Test
    void 기준값이_잘못되면_409_INVALID_RULE_PARAMS이고_경보는_롤백_배치는_FAILED() {
        LocalDate d = LocalDate.of(2022, 9, 12);
        String target = newKey();
        for (int i = 0; i < 5; i++) {
            inbound(target, newKey(), d.atTime(8, 0), "1", "US Dollar");
        }
        setActive("R02_FAN_IN", 1, false);
        jdbc.update("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
                values ('R02_FAN_IN', 2, '기준값 없음', 'FAN_IN', '{}'::jsonb, true, 1, 1)""");
        try {
            long runId = runRuleScan.start(d, SYSTEM);
            assertThatThrownBy(() -> runRuleScan.run(runId, d)).isInstanceOf(InvalidRuleParamsException.class);

            assertThat(alertsOf(target)).isEmpty();
            Map<String, Object> run = jdbc.queryForMap(
                    "select status, error_message from ops.batch_runs where id = ?", runId);
            assertThat(run.get("status")).isEqualTo("FAILED");
            assertThat((String) run.get("error_message")).startsWith("INVALID_RULE_PARAMS");
        } finally {
            setActive("R02_FAN_IN", 2, false);
            setActive("R02_FAN_IN", 1, true);
        }
    }

    // ---- helpers ----

    private static String newKey() {
        return "10_R" + SEQUENCE.incrementAndGet();
    }

    /** 법인·계좌 행이 있는 계좌 — entity_id 를 accounts 에서 가져오는지 본다. */
    private String accountWithEntity() {
        String key = newKey();
        jdbc.update("insert into ops.entities(entity_id, entity_name, entity_type) values (?, '테스트 법인', 'CORPORATION')",
                "E_" + key);
        jdbc.update("insert into ops.accounts(account_key, bank_id, account_no, entity_id) values (?, '10', ?, ?)",
                key, key.substring(3), "E_" + key);
        return key;
    }

    private long inbound(String to, String from, LocalDateTime ts, String amount, String currency) {
        return transaction(from, to, ts, amount, currency, false);
    }

    private void selfTransfer(String account, LocalDateTime ts) {
        transaction(account, account, ts, "1", "US Dollar", true);
    }

    private long transaction(String from, String to, LocalDateTime ts, String amount, String currency, boolean self) {
        if (sourceFileId == null) {
            sourceFileId = jdbc.queryForObject(
                    "insert into ops.source_files(file_name, file_hash) values ('scan.csv', ?) returning id",
                    Long.class, String.format("%064d", 99));
        }
        return jdbc.queryForObject("""
                insert into ops.transactions(ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values (?, '10', ?, ?, '10', ?, ?, ?::numeric, ?, ?::numeric, ?, 'ACH', false, ?, ?, ?, '010', '010')
                returning transaction_id""", Long.class,
                ts, from.substring(3), from, to.substring(3), to, amount, currency, amount, currency, self,
                sourceFileId, SEQUENCE.incrementAndGet());
    }

    private List<Map<String, Object>> alertsOf(String accountKey) {
        return jdbc.queryForList("""
                select id, alert_no, entity_id, window_start, window_end, reference_ts, batch_date, score_status,
                       rule_facts::text as facts
                from ops.alerts where account_key = ? order by reference_ts""", accountKey);
    }

    private List<Long> linkedIds(long alertId) {
        return jdbc.queryForList(
                "select transaction_id from ops.alert_transactions where alert_id = ? order by transaction_id",
                Long.class, alertId);
    }

    private JsonNode facts(Map<String, Object> alert) {
        try {
            return om.readTree((String) alert.get("facts"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void setActive(String ruleId, int version, boolean active) {
        jdbc.update("update ops.rules set is_active = ?, updated_at = now(), updated_by = 1 "
                + "where rule_id = ? and version = ?", active, ruleId, version);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static LocalDateTime ts(Object value) {
        return ((Timestamp) value).toLocalDateTime();
    }
}
