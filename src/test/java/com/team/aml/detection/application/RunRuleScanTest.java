package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.domain.CycleRule;
import com.team.aml.detection.domain.DatasetNotLoadedException;
import com.team.aml.detection.domain.DateOutOfRangeException;
import com.team.aml.detection.domain.InvalidRuleParamsException;
import com.team.aml.detection.domain.NoActiveRulesException;
import com.team.aml.detection.domain.RuleScanRepository;
import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.security.CurrentUser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * 룰 스캔 여섯 룰 R01~R06 (SCRUM-188·99·193·192·194·189). V011·V016 각 룰 v1 (96시간, R01 N=10·R02 N=5·R03 ±10%·R04 4홉·R05 중간 3곳·R06 각각 5~100)로 돈다.
 * 경보는 지울 수 없고 같은 날짜는 한 번만 성공하므로 테스트마다 날짜·계좌를 따로 쓰고, 단언은 그 계좌의 경보만 본다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunRuleScanTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SYSTEM = CurrentUser.SYSTEM_ID;
    private static final List<String> SEEDED = List.of("R01_FAN_OUT", "R02_FAN_IN", "R03_FX_RELAY",
            "R04_CYCLE", "R05_SCATTER_GATHER", "R06_GATHER_SCATTER");

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

    @Autowired
    RuleScanRepository scans;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MeterRegistry meters;

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
        assertThat(result.alertsByRule()).extracting(RuleAlertCount::ruleId)
                .containsExactly("R01_FAN_OUT", "R02_FAN_IN", "R03_FX_RELAY", "R04_CYCLE", "R05_SCATTER_GATHER", "R06_GATHER_SCATTER");   // rule_id 순
        assertThat(result.alertsByRule()).allSatisfy(r -> assertThat(r.ruleVersion()).isEqualTo(1));
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
        SEEDED.forEach(r -> setActive(r, 1, false));
        try {
            assertThatThrownBy(() -> runRuleScan.start(d, SYSTEM)).isInstanceOf(NoActiveRulesException.class);
        } finally {
            SEEDED.forEach(r -> setActive(r, 1, true));
        }
    }

    @Test
    void 시드로_여섯_룰이_켜져_있고_여섯_룰_모두_스캔해_센다() {
        LocalDate d = LocalDate.of(2022, 9, 13);
        inbound(newKey(), newKey(), d.atTime(1, 0), "1", "US Dollar");

        assertThat(jdbc.queryForList(
                "select rule_id from ops.rules where is_active and version = 1 order by rule_id", String.class))
                .containsExactlyElementsOf(SEEDED);
        assertThat(count("select count(*) from ops.rules r, jsonb_object_keys(r.params->'usd_rates') "
                + "where r.rule_id = 'R03_FX_RELAY'")).isEqualTo(15);   // rules.md 환율 표 15개 통화

        RuleScanResult result = runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(result.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
        assertThat(result.alertsByRule()).extracting(RuleAlertCount::ruleId)
                .containsExactly("R01_FAN_OUT", "R02_FAN_IN", "R03_FX_RELAY", "R04_CYCLE", "R05_SCATTER_GATHER", "R06_GATHER_SCATTER");
    }

    @Test
    void 룰마다_걸린_시간과_끝난_배치를_지표로_남기고_태그에_날짜_번호가_없다() {   // SCRUM-349
        LocalDate d = LocalDate.of(2022, 12, 1);
        inbound(newKey(), newKey(), d.atTime(1, 0), "1", "US Dollar");
        long ruleBefore = timerCount("aml.rule.scan.duration", "rule", "R02_FAN_IN");
        long batchBefore = timerCount("aml.batch.duration", "type", "RULE_SCAN", "result", "SUCCEEDED");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        for (String rule : SEEDED) {
            assertThat(timerCount("aml.rule.scan.duration", "rule", rule)).as(rule).isPositive();
        }
        assertThat(timerCount("aml.rule.scan.duration", "rule", "R02_FAN_IN")).isEqualTo(ruleBefore + 1);
        assertThat(timerCount("aml.batch.duration", "type", "RULE_SCAN", "result", "SUCCEEDED"))
                .isEqualTo(batchBefore + 1);   // 스캔 트랜잭션이 커밋된 뒤 한 번
        assertThat(meters.get("aml.rule.scan.duration").timers()).allSatisfy(t ->
                assertThat(t.getId().getTags()).extracting(Tag::getKey).containsExactly("rule"));
        assertThat(meters.get("aml.batch.duration").timers()).allSatisfy(t ->
                assertThat(t.getId().getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("type", "result"));
    }

    private long timerCount(String name, String... tags) {
        Timer timer = meters.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void R01_수신_계좌가_N개가_되는_분에_경보_1건이고_금액은_보낸_쪽() {
        LocalDate d = LocalDate.of(2022, 9, 14);
        String source = accountWithEntity();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {   // 보낸 쪽 10 USD, 받은 쪽 8.5 EUR — rule_facts 는 보낸 쪽
            ids.add(transfer(source, newKey(), d.atTime(14, i), "10", "US Dollar", "8.5", "Euro"));
        }
        transfer(source, newKey(), d.atTime(14, 30), "3", "Yen", "3", "Yen");   // 참 유지 — 새 경보 없음

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> alerts = alertsOf(source);
        assertThat(alerts).hasSize(1);
        Map<String, Object> a = alerts.get(0);
        assertThat(a.get("rule_id")).isEqualTo("R01_FAN_OUT");
        assertThat(ts(a.get("window_start"))).isEqualTo(d.minusDays(4).atTime(14, 10));
        assertThat(ts(a.get("window_end"))).isEqualTo(d.atTime(14, 10));
        assertThat(ts(a.get("reference_ts"))).isEqualTo(d.atTime(14, 10));
        assertThat(a.get("entity_id")).isEqualTo("E_" + source);
        assertThat(linkedIds((Long) a.get("id"))).containsExactlyElementsOf(ids);

        JsonNode facts = facts(a);
        assertThat(facts.get("distinct_receivers").asInt()).isEqualTo(10);
        assertThat(facts.has("distinct_senders")).isFalse();
        assertThat(facts.get("amounts_by_currency")).singleElement().satisfies(c -> {
            assertThat(c.get("currency").asText()).isEqualTo("US Dollar");
            assertThat(c.get("total_amount").decimalValue()).isEqualByComparingTo("100");
            assertThat(c.get("transaction_count").asInt()).isEqualTo(10);
        });
    }

    @Test
    void R01은_같은_수신_계좌를_한_번만_세고_자기_계좌_거래는_뺀다() {
        LocalDate d = LocalDate.of(2022, 9, 15);
        String source = newKey();
        String repeated = newKey();
        for (int i = 0; i < 8; i++) {
            transfer(source, newKey(), d.atTime(9, i), "1", "US Dollar", "1", "US Dollar");
        }
        transfer(source, repeated, d.atTime(9, 10), "1", "US Dollar", "1", "US Dollar");
        transfer(source, repeated, d.atTime(9, 11), "1", "US Dollar", "1", "US Dollar");   // 9곳 그대로
        selfTransfer(source, d.atTime(9, 12));                                              // 세지 않음

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(source)).isEmpty();
    }

    @Test
    void R01_전날까지_N_미만이던_계좌는_그날_N이_되는_분에_경보_이미_N_이상이던_계좌는_없음() {
        LocalDate d = LocalDate.of(2022, 9, 17);   // 전날 9/16 은 그날 모든 분의 1분 전 창에 들어간다 — 후보 거르기 경계
        String crossing = newKey();
        for (int i = 0; i < 9; i++) {
            transfer(crossing, newKey(), d.minusDays(1).atTime(12, i), "1", "US Dollar", "1", "US Dollar");
        }
        transfer(crossing, newKey(), d.atTime(10, 0), "1", "US Dollar", "1", "US Dollar");   // 10번째
        String hub = newKey();
        for (int i = 0; i < 10; i++) {
            transfer(hub, newKey(), d.minusDays(1).atTime(12, i), "1", "US Dollar", "1", "US Dollar");
        }
        transfer(hub, newKey(), d.atTime(10, 0), "1", "US Dollar", "1", "US Dollar");        // 참이 이어지는 중

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(crossing)).singleElement().satisfies(a -> {
            assertThat(a.get("rule_id")).isEqualTo("R01_FAN_OUT");
            assertThat(ts(a.get("reference_ts"))).isEqualTo(d.atTime(10, 1));
            assertThat(facts(a).get("distinct_receivers").asInt()).isEqualTo(10);
        });
        assertThat(alertsOf(hub)).isEmpty();
    }

    @Test
    void R06_입금_상대와_출금_상대가_둘_다_하한에_닿는_분에_경보_1건() {
        LocalDate d = LocalDate.of(2022, 9, 18);
        String hub = accountWithEntity();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {   // 입금: 보낸 쪽 12 USD, 받은 쪽 10 EUR — inbound 는 받은 쪽
            ids.add(transfer(newKey(), hub, d.atTime(8, i), "12", "US Dollar", "10", "Euro"));
        }
        for (int i = 0; i < 5; i++) {   // 출금: 보낸 쪽 7 USD, 받은 쪽 6 EUR — outbound 는 보낸 쪽
            ids.add(transfer(hub, newKey(), d.atTime(9, i), "7", "US Dollar", "6", "Euro"));
        }
        transfer(hub, newKey(), d.atTime(9, 30), "1", "Yen", "1", "Yen");   // 6곳 — 참 유지, 새 경보 없음

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> r06 = alertsOf(hub).stream()
                .filter(a -> "R06_GATHER_SCATTER".equals(a.get("rule_id"))).toList();   // 입금 5곳이라 R02 경보는 따로 생긴다
        assertThat(r06).hasSize(1);
        Map<String, Object> a = r06.get(0);
        assertThat(ts(a.get("window_start"))).isEqualTo(d.minusDays(4).atTime(9, 5));
        assertThat(ts(a.get("reference_ts"))).isEqualTo(d.atTime(9, 5));
        assertThat(a.get("entity_id")).isEqualTo("E_" + hub);
        assertThat(linkedIds((Long) a.get("id"))).containsExactlyElementsOf(ids);

        JsonNode facts = facts(a);
        assertThat(facts.get("distinct_senders").asInt()).isEqualTo(5);
        assertThat(facts.get("distinct_receivers").asInt()).isEqualTo(5);
        assertThat(facts.get("inbound_by_currency")).singleElement().satisfies(c -> {
            assertThat(c.get("currency").asText()).isEqualTo("Euro");
            assertThat(c.get("total_amount").decimalValue()).isEqualByComparingTo("50");
            assertThat(c.get("transaction_count").asInt()).isEqualTo(5);
        });
        assertThat(facts.get("outbound_by_currency")).singleElement().satisfies(c -> {
            assertThat(c.get("currency").asText()).isEqualTo("US Dollar");
            assertThat(c.get("total_amount").decimalValue()).isEqualByComparingTo("35");
            assertThat(c.get("transaction_count").asInt()).isEqualTo(5);
        });
    }

    @Test
    void R06은_한쪽만_하한이거나_상한을_넘으면_경보_없음() {
        LocalDate d = LocalDate.of(2022, 9, 19);
        String oneSided = newKey();   // 입금 5곳·출금 4곳
        String tooMany = newKey();    // 입금 101곳·출금 5곳
        for (int i = 0; i < 5; i++) {
            transfer(newKey(), oneSided, d.atTime(8, i), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 4; i++) {
            transfer(oneSided, newKey(), d.atTime(9, i), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 101; i++) {
            transfer(newKey(), tooMany, d.atTime(8, 0), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 5; i++) {
            transfer(tooMany, newKey(), d.atTime(9, i), "1", "US Dollar", "1", "US Dollar");
        }

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(oneSided)).noneMatch(a -> "R06_GATHER_SCATTER".equals(a.get("rule_id")));
        assertThat(alertsOf(tooMany)).noneMatch(a -> "R06_GATHER_SCATTER".equals(a.get("rule_id")));
    }

    @Test
    void R06_만료만으로_참이_된_뒤_첫_거래_분에도_1분_전_창이_이미_참이면_경보_없음() {
        // 노션 v2 3.1·오프라인 분석과 같은 B7 비교(9/1~9/10 R06 401건 일치). 9/25~9/28 은 다른 테스트가 스캔하지 않는다.
        LocalDate d = LocalDate.of(2022, 9, 28);
        String hub = newKey();
        transfer(newKey(), hub, LocalDateTime.of(2022, 9, 24, 10, 0), "1", "US Dollar", "1", "US Dollar");   // 9/28 10:00 부터 창 밖
        List<String> senders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {   // 위 1곳과 합쳐 101곳 — 상한 초과로 거짓
            String s = newKey();
            senders.add(s);
            transfer(s, hub, LocalDateTime.of(2022, 9, 25, 8, 0), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 5; i++) {
            transfer(hub, newKey(), LocalDateTime.of(2022, 9, 26, 8, i), "1", "US Dollar", "1", "US Dollar");
        }
        // 10:00 에 만료로 100곳(참)이 된 뒤 첫 거래 — 기존 상대가 다시 입금. 지금 창·1분 전 창 둘 다 100곳·5곳이라 참이 이어지는 것
        transfer(senders.get(0), hub, d.atTime(10, 3), "1", "US Dollar", "1", "US Dollar");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(hub)).noneMatch(a -> "R06_GATHER_SCATTER".equals(a.get("rule_id")));
    }

    @Test
    void R06_상한_후보_거르기는_공통_구간_시작_1분_전_상대를_세지_않는다() {
        // #127 리뷰(채영). 공통 구간 = [d+1 00:00 - 96h, d 00:01) = [d-3 00:00, ...). 구간을 1분만 앞당겨도 101곳으로 걸러져 경보가 사라진다.
        // 10/1~10/5 는 다른 테스트가 스캔하지 않는다.
        LocalDate d = LocalDate.of(2022, 10, 5);
        String hub = accountWithEntity();
        transfer(newKey(), hub, d.minusDays(4).atTime(23, 59), "1", "US Dollar", "1", "US Dollar");   // 공통 구간 1분 전
        List<String> senders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {   // 공통 구간 안 100곳
            String s = newKey();
            senders.add(s);
            transfer(s, hub, d.minusDays(2).atTime(12, 0), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 5; i++) {     // 공통 구간 안 출금 5곳
            transfer(hub, newKey(), d.minusDays(2).atTime(13, i), "1", "US Dollar", "1", "US Dollar");
        }
        // d 23:59 기존 상대 재입금 — 지금 창 [d-3 00:00, d+1 00:00) 100곳·5곳(참), 1분 전 창 [d-4 23:59, d 23:59) 101곳(거짓)
        transfer(senders.get(0), hub, d.atTime(23, 59), "1", "US Dollar", "1", "US Dollar");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> r06 = alertsOf(hub).stream()
                .filter(a -> "R06_GATHER_SCATTER".equals(a.get("rule_id"))).toList();
        assertThat(r06).singleElement().satisfies(a -> {
            assertThat(ts(a.get("reference_ts"))).isEqualTo(d.plusDays(1).atStartOfDay());
            assertThat(facts(a).get("distinct_senders").asInt()).isEqualTo(100);
            assertThat(facts(a).get("distinct_receivers").asInt()).isEqualTo(5);
        });
    }

    @Test
    void R03_받은_돈을_늦은_분에_다른_통화로_같은_가치만큼_보내면_경보_1건() {
        LocalDate d = LocalDate.of(2022, 9, 22);   // 9/18~9/22 에 스캔하는 다른 테스트 계좌와 섞이지 않게 새 계좌만 쓴다
        String m = accountWithEntity();
        long a = transfer(newKey(), m, d.atTime(10, 0), "100", "US Dollar", "100", "US Dollar");   // 받은 쪽 100 USD
        long b = transfer(m, newKey(), d.atTime(11, 0), "85.34", "Euro", "85.34", "Euro");        // 보낸 쪽 85.34 EUR = 100 USD
        transfer(m, newKey(), d.atTime(12, 0), "85", "Euro", "85", "Euro");                      // 두 번째 쌍 — 참 유지, 새 경보 없음

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> r03 = alertsOf(m).stream()
                .filter(x -> "R03_FX_RELAY".equals(x.get("rule_id"))).toList();
        assertThat(r03).hasSize(1);
        Map<String, Object> alert = r03.get(0);
        assertThat(ts(alert.get("window_start"))).isEqualTo(d.minusDays(4).atTime(11, 1));
        assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(11, 1));
        assertThat(alert.get("entity_id")).isEqualTo("E_" + m);
        assertThat(linkedIds((Long) alert.get("id"))).containsExactly(a, b);

        JsonNode facts = facts(alert);
        assertThat(facts.get("paths").toString()).isEqualTo("[[" + a + "," + b + "]]");
        JsonNode relay = facts.get("relays").get(0);
        assertThat(relay.get("in_currency").asText()).isEqualTo("US Dollar");
        assertThat(relay.get("in_amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(relay.get("out_currency").asText()).isEqualTo("Euro");
        assertThat(relay.get("out_amount").decimalValue()).isEqualByComparingTo("85.34");
        assertThat(relay.get("ratio").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void R03_비율_0_9는_경보_그_아래와_같은_분과_같은_통화와_표에_없는_통화는_경보_없음() {
        LocalDate d = LocalDate.of(2022, 9, 23);
        String edge = newKey();          // 100 USD → 76.806 EUR = 비율 0.9 (포함)
        String low = newKey();           // 100 USD → 76.805 EUR
        String sameMinute = newKey();    // 같은 분에 받고 보냄
        String sameCurrency = newKey();  // USD → USD
        String unknown = newKey();       // 표에 없는 통화
        transfer(newKey(), edge, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(edge, newKey(), d.atTime(9, 1), "76.806", "Euro", "76.806", "Euro");
        transfer(newKey(), low, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(low, newKey(), d.atTime(9, 1), "76.805", "Euro", "76.805", "Euro");
        transfer(newKey(), sameMinute, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(sameMinute, newKey(), d.atTime(9, 0), "85.34", "Euro", "85.34", "Euro");
        transfer(newKey(), sameCurrency, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(sameCurrency, newKey(), d.atTime(9, 1), "100", "US Dollar", "100", "US Dollar");
        transfer(newKey(), unknown, d.atTime(9, 0), "100", "Dinar", "100", "Dinar");
        transfer(unknown, newKey(), d.atTime(9, 1), "85.34", "Euro", "85.34", "Euro");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(alertsOf(edge)).anyMatch(x -> "R03_FX_RELAY".equals(x.get("rule_id")));
        for (String k : List.of(low, sameMinute, sameCurrency, unknown)) {
            assertThat(alertsOf(k)).noneMatch(x -> "R03_FX_RELAY".equals(x.get("rule_id")));
        }
    }

    @Test
    void R03_DB에_저장된_기준값의_소수_자리를_잃지_않는다() {
        // #리뷰: 기준값을 double 로 읽으면 0.099999999999999999 가 0.1 이 돼 비율 정확히 1.1 인 쌍이 잘못 들어간다.
        // 11/2 는 다른 테스트가 쓰지 않는다. R03 v2 행을 잠깐 켜고 끝나면 v1 로 돌린다.
        LocalDate d = LocalDate.of(2022, 11, 2);
        String exact = newKey();    // 100 USD → 93.874 EUR = 비율 정확히 1.1 → 허용 오차 0.0999…9 면 밖
        String inside = newKey();   // 100 USD → 93.873 EUR = 비율 1.09998… → 안
        transfer(newKey(), exact, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(exact, newKey(), d.atTime(9, 1), "93.874", "Euro", "93.874", "Euro");
        transfer(newKey(), inside, d.atTime(9, 0), "100", "US Dollar", "100", "US Dollar");
        transfer(inside, newKey(), d.atTime(9, 1), "93.873", "Euro", "93.873", "Euro");
        setActive("R03_FX_RELAY", 1, false);
        jdbc.update("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
                select rule_id, 2, name, typology_hint,
                       jsonb_set(params, '{amount_tolerance}', '0.099999999999999999'::jsonb), true, 1, 1
                from ops.rules where rule_id = 'R03_FX_RELAY' and version = 1""");
        try {
            runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

            assertThat(alertsOf(exact)).noneMatch(x -> "R03_FX_RELAY".equals(x.get("rule_id")));
            assertThat(alertsOf(inside)).anyMatch(x -> "R03_FX_RELAY".equals(x.get("rule_id")));
        } finally {
            setActive("R03_FX_RELAY", 2, false);
            setActive("R03_FX_RELAY", 1, true);
        }
    }

    @Test
    void R05_흩은_돈이_중간_계좌_3곳을_거쳐_한_계좌로_모이면_경보_1건이고_대표_경로만_paths에() {
        LocalDate d = LocalDate.of(2022, 11, 5);   // 11/1~11/5 에 스캔하는 다른 테스트 계좌와 섞이지 않게 새 계좌만 쓴다
        String s = newKey();
        String t = accountWithEntity();
        List<String> m = List.of(newKey(), newKey(), newKey());
        long a0 = transfer(s, m.get(0), d.atTime(8, 0), "1", "US Dollar", "1", "US Dollar");    // M1 대표
        long a0b = transfer(s, m.get(0), d.atTime(8, 30), "1", "US Dollar", "1", "US Dollar");  // M1 유효하지만 대표 아님
        long a1 = transfer(s, m.get(1), d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
        long a2 = transfer(s, m.get(2), d.atTime(9, 5), "1", "US Dollar", "1", "US Dollar");
        long b0 = transfer(m.get(0), t, d.atTime(10, 0), "1", "US Dollar", "1", "US Dollar");
        long b1 = transfer(m.get(1), t, d.atTime(10, 1), "1", "US Dollar", "1", "US Dollar");
        long b2 = transfer(m.get(2), t, d.atTime(10, 2), "1", "US Dollar", "1", "US Dollar");   // 세 번째 — 경보
        transfer(m.get(2), t, d.atTime(11, 0), "1", "US Dollar", "1", "US Dollar");             // 참 유지 — 새 경보 없음

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> r05 = alertsOf(t).stream()
                .filter(x -> "R05_SCATTER_GATHER".equals(x.get("rule_id"))).toList();
        assertThat(r05).singleElement().satisfies(alert -> {
            assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(10, 3));
            assertThat(ts(alert.get("window_start"))).isEqualTo(d.minusDays(4).atTime(10, 3));
            assertThat(alert.get("entity_id")).isEqualTo("E_" + t);
            assertThat(linkedIds((Long) alert.get("id"))).containsExactlyInAnyOrder(a0, a0b, a1, a2, b0, b1, b2);
            JsonNode facts = facts(alert);
            assertThat(facts.get("source_account_key").asText()).isEqualTo(s);
            assertThat(facts.get("intermediates").asInt()).isEqualTo(3);
            assertThat(facts.get("paths").toString())
                    .isEqualTo("[[" + a0 + "," + b0 + "],[" + a1 + "," + b1 + "],[" + a2 + "," + b2 + "]]");
        });
    }

    @Test
    void R05는_중간_계좌가_2곳이거나_같은_분이거나_출발과_도착이_같으면_경보_없음() {
        LocalDate d = LocalDate.of(2022, 11, 8);
        String s1 = newKey();   // 중간 계좌 2곳
        String t1 = newKey();
        for (int i = 0; i < 2; i++) {
            String mid = newKey();
            transfer(s1, mid, d.atTime(9, i), "1", "US Dollar", "1", "US Dollar");
            transfer(mid, t1, d.atTime(10, i), "1", "US Dollar", "1", "US Dollar");
        }
        String s2 = newKey();   // 세 번째 중간 계좌는 같은 분에 받고 보냄
        String t2 = newKey();
        for (int i = 0; i < 3; i++) {
            String mid = newKey();
            transfer(s2, mid, d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
            transfer(mid, t2, i < 2 ? d.atTime(10, i) : d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
        }
        String s3 = newKey();   // S → M1·M2·M3 → S (도착 = 출발)
        for (int i = 0; i < 3; i++) {
            String mid = newKey();
            transfer(s3, mid, d.atTime(9, i), "1", "US Dollar", "1", "US Dollar");
            transfer(mid, s3, d.atTime(10, i), "1", "US Dollar", "1", "US Dollar");
        }

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        for (String t : List.of(t1, t2, s3)) {
            assertThat(alertsOf(t)).noneMatch(x -> "R05_SCATTER_GATHER".equals(x.get("rule_id")));
        }
    }

    @Test
    void R04_A로_돌아오는_3홉_고리에_경보_1건이고_창에_남아_있어도_다른_분에_닫힌_고리는_새_경보() {
        LocalDate d = LocalDate.of(2022, 10, 12);   // 10/8~10/12 는 다른 테스트가 스캔하지 않는다
        String a = accountWithEntity();
        String b = newKey();
        String c = newKey();
        long t1 = transfer(a, b, d.atTime(10, 0), "100", "US Dollar", "100", "US Dollar");
        long t2 = transfer(b, c, d.atTime(10, 5), "90", "Euro", "90", "Euro");   // 통화가 바뀌어도 금액 조건 없음
        long t3 = transfer(c, a, d.atTime(10, 10), "80", "Euro", "80", "Euro");
        String x = newKey();
        long x1 = transfer(a, x, d.atTime(11, 0), "1", "US Dollar", "1", "US Dollar");
        long x2 = transfer(x, a, d.atTime(11, 5), "1", "US Dollar", "1", "US Dollar");   // 첫 고리가 창 안이어도 새 경보 (사건형)

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(r04Of(a)).satisfiesExactly(
                alert -> {
                    assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(10, 11));
                    assertThat(ts(alert.get("window_start"))).isEqualTo(d.minusDays(4).atTime(10, 11));
                    assertThat(alert.get("entity_id")).isEqualTo("E_" + a);
                    assertThat(linkedIds((Long) alert.get("id"))).containsExactly(t1, t2, t3);
                    assertThat(facts(alert).toString())
                            .isEqualTo("{\"paths\":[[" + t1 + "," + t2 + "," + t3 + "]],\"hops_by_path\":[3]}");
                },
                alert -> {
                    assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(11, 6));
                    assertThat(linkedIds((Long) alert.get("id"))).containsExactly(x1, x2);
                    assertThat(facts(alert).toString())
                            .isEqualTo("{\"paths\":[[" + x1 + "," + x2 + "]],\"hops_by_path\":[2]}");
                });
        assertThat(r04Of(b)).isEmpty();   // 같은 고리를 B·C 쪽에서 또 잡지 않음
        assertThat(r04Of(c)).isEmpty();
    }

    @Test
    void R04는_같은_분에_돌아오거나_5홉이면_경보_없음() {
        LocalDate d = LocalDate.of(2022, 10, 15);
        String sameMinute = newKey();
        String back = newKey();
        transfer(sameMinute, back, d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
        transfer(back, sameMinute, d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
        List<String> ring = List.of(newKey(), newKey(), newKey(), newKey(), newKey());   // A→B→C→D→E→A
        for (int i = 0; i < 5; i++) {
            transfer(ring.get(i), ring.get((i + 1) % 5), d.atTime(10, i), "1", "US Dollar", "1", "US Dollar");
        }

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(r04Of(sameMinute)).isEmpty();
        assertThat(r04Of(back)).isEmpty();
        for (String k : ring) {
            assertThat(r04Of(k)).isEmpty();
        }
    }

    @Test
    void R04_이미_경보에_쓴_거래도_나중에_닫힌_고리의_일부로_다시_근거에_들어간다() {
        // A→B 09:00, B→A 10:00, A→B 11:00, B→A 12:00 — 12:00 경보에 (09:00, 12:00)·(11:00, 12:00) 두 경로
        LocalDate d = LocalDate.of(2022, 10, 17);
        String a = accountWithEntity();
        String b = newKey();
        long ab9 = transfer(a, b, d.atTime(9, 0), "1", "US Dollar", "1", "US Dollar");
        long ba10 = transfer(b, a, d.atTime(10, 0), "1", "US Dollar", "1", "US Dollar");
        long ab11 = transfer(a, b, d.atTime(11, 0), "1", "US Dollar", "1", "US Dollar");
        long ba12 = transfer(b, a, d.atTime(12, 0), "1", "US Dollar", "1", "US Dollar");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(r04Of(a)).satisfiesExactly(
                alert -> {
                    assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(10, 1));
                    assertThat(linkedIds((Long) alert.get("id"))).containsExactly(ab9, ba10);
                    assertThat(facts(alert).toString())
                            .isEqualTo("{\"paths\":[[" + ab9 + "," + ba10 + "]],\"hops_by_path\":[2]}");
                },
                alert -> {
                    assertThat(ts(alert.get("reference_ts"))).isEqualTo(d.atTime(12, 1));
                    assertThat(linkedIds((Long) alert.get("id"))).containsExactly(ab9, ab11, ba12);
                    assertThat(facts(alert).toString()).isEqualTo("{\"paths\":[[" + ab9 + "," + ba12 + "],[" + ab11
                            + "," + ba12 + "]],\"hops_by_path\":[2,2]}");
                });
        // B→A 10:00 → A→B 11:00 은 B 에서 시작해 B 로 돌아오는 고리라 대상 계좌 B 의 경보가 따로 생긴다
        assertThat(r04Of(b)).singleElement()
                .satisfies(alert -> assertThat(linkedIds((Long) alert.get("id"))).containsExactly(ba10, ab11));
    }

    @Test
    void R04_같은_분에_닫힌_고리_여럿은_경보_1건이고_근거_거래는_한_번만() {
        LocalDate d = LocalDate.of(2022, 10, 19);
        String a = accountWithEntity();
        String b = newKey();
        long ab1 = transfer(a, b, d.atTime(10, 0), "1", "US Dollar", "1", "US Dollar");
        long ab2 = transfer(a, b, d.atTime(10, 1), "1", "US Dollar", "1", "US Dollar");
        long ba = transfer(b, a, d.atTime(10, 5), "1", "US Dollar", "1", "US Dollar");

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        assertThat(r04Of(a)).singleElement().satisfies(alert -> {
            assertThat(linkedIds((Long) alert.get("id"))).containsExactly(ab1, ab2, ba);
            assertThat(facts(alert).toString()).isEqualTo("{\"paths\":[[" + ab1 + "," + ba + "],[" + ab2 + ","
                    + ba + "]],\"hops_by_path\":[2,2]}");
        });
    }

    @Test
    void R04_자정_넘어_닫힌_4홉_고리는_닫힌_날_경보이고_다음_날_스캔에서_다시_경보가_안_된다() {
        LocalDate d1 = LocalDate.of(2022, 10, 22);   // 10/22·10/23 은 다른 테스트가 스캔하지 않는다
        LocalDate d2 = d1.plusDays(1);
        String a = accountWithEntity();
        fourHop(a, d1.minusDays(1).atTime(23, 50));                        // 10/21 23:53 닫힘 — 10/22 스캔 대상 아님
        List<Long> midnight = fourHop(a, d1.minusDays(1).atTime(23, 58));   // 23:58·23:59·00:00·00:01 — 10/22 닫힘
        transfer(newKey(), newKey(), d2.atTime(12, 0), "1", "US Dollar", "1", "US Dollar");   // 고리와 무관한 10/23 거래 — 혼자 돌려도 10/23 이 데이터 범위 안 (#리뷰)

        runRuleScan.run(runRuleScan.start(d1, SYSTEM), d1);
        runRuleScan.run(runRuleScan.start(d2, SYSTEM), d2);

        assertThat(r04Of(a)).singleElement().satisfies(alert -> {
            assertThat(ts(alert.get("reference_ts"))).isEqualTo(d1.atTime(0, 2));
            assertThat(alert.get("batch_date")).isEqualTo(Date.valueOf(d1));
            assertThat(linkedIds((Long) alert.get("id"))).containsExactlyElementsOf(midnight);
            assertThat(facts(alert).toString())
                    .isEqualTo(("{\"paths\":[" + midnight + "],\"hops_by_path\":[4]}").replace(" ", ""));
        });
    }

    @Test
    void R04_고리가_커서_한_번에_받는_1000행을_넘어도_받는_도중에_묶음마다_저장한다() {
        // #리뷰: 하루치 경로를 한꺼번에 올리지 않고 커서로 나눠 받으며 같은 연결로 경보를 저장 — 둘째 묶음부터도 빠짐없이
        LocalDate d = LocalDate.of(2022, 10, 25);
        String a = accountWithEntity();
        String b = newKey();
        for (int i = 0; i < 40; i++) {
            transfer(a, b, d.atTime(1, i), "1", "US Dollar", "1", "US Dollar");
        }
        for (int i = 0; i < 40; i++) {   // 돌아오는 거래마다 앞 A→B 40건과 고리 → 40 × 40 = 1,600 행
            transfer(b, a, d.atTime(2, i), "1", "US Dollar", "1", "US Dollar");
        }

        runRuleScan.run(runRuleScan.start(d, SYSTEM), d);

        List<Map<String, Object>> r04 = r04Of(a);
        assertThat(r04).hasSize(40);
        assertThat(r04).allSatisfy(alert -> {
            assertThat(facts(alert).get("paths")).hasSize(40);
            assertThat(linkedIds((Long) alert.get("id"))).hasSize(41);
        });
    }

    @Test
    void R04_고리_조회_동안만_merge_join을_끄고_끝나면_되돌린다() {   // SCRUM-311 — 같은 스캔의 다른 룰 쿼리에 번지지 않게
        LocalDate d = LocalDate.of(2022, 11, 14);
        String a = newKey();
        String b = newKey();
        transfer(a, b, d.atTime(1, 0), "1", "US Dollar", "1", "US Dollar");
        transfer(b, a, d.atTime(1, 5), "1", "US Dollar", "1", "US Dollar");
        List<String> during = new ArrayList<>();

        String after = new TransactionTemplate(transactionManager).execute(status -> {
            scans.forEachCycleGroup(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), new CycleRule(96, 4),
                    group -> during.add(jdbc.queryForObject("show enable_mergejoin", String.class)));
            return jdbc.queryForObject("show enable_mergejoin", String.class);
        });

        assertThat(during).containsExactly("off");   // 고리 1개 = 묶음 1개
        assertThat(after).isEqualTo("on");
    }

    private List<Map<String, Object>> r04Of(String accountKey) {
        return alertsOf(accountKey).stream().filter(r -> "R04_CYCLE".equals(r.get("rule_id"))).toList();
    }

    /** a → x → y → z → a, start 부터 1분 간격. 거래 id 를 시간순으로. */
    private List<Long> fourHop(String a, LocalDateTime start) {
        String x = newKey();
        String y = newKey();
        String z = newKey();
        return List.of(
                transfer(a, x, start, "1", "US Dollar", "1", "US Dollar"),
                transfer(x, y, start.plusMinutes(1), "1", "US Dollar", "1", "US Dollar"),
                transfer(y, z, start.plusMinutes(2), "1", "US Dollar", "1", "US Dollar"),
                transfer(z, a, start.plusMinutes(3), "1", "US Dollar", "1", "US Dollar"));
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
        return transfer(from, to, ts, amount, currency, amount, currency, self);
    }

    /** 보낸 쪽·받은 쪽 금액이 다른 거래 — R01(보낸 쪽)·R02(받은 쪽)가 방향대로 읽는지 본다. */
    private long transfer(String from, String to, LocalDateTime ts, String paid, String paidCurrency,
                          String received, String receivedCurrency) {
        return transfer(from, to, ts, paid, paidCurrency, received, receivedCurrency, false);
    }

    private long transfer(String from, String to, LocalDateTime ts, String paid, String paidCurrency,
                          String received, String receivedCurrency, boolean self) {
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
                ts, from.substring(3), from, to.substring(3), to, received, receivedCurrency, paid, paidCurrency, self,
                sourceFileId, SEQUENCE.incrementAndGet());
    }

    private List<Map<String, Object>> alertsOf(String accountKey) {
        return jdbc.queryForList("""
                select a.id, a.alert_no, r.rule_id, a.entity_id, a.window_start, a.window_end, a.reference_ts,
                       a.batch_date, a.score_status, a.rule_facts::text as facts
                from ops.alerts a join ops.rules r on r.id = a.rule_ref_id
                where a.account_key = ? order by a.reference_ts""", accountKey);
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
