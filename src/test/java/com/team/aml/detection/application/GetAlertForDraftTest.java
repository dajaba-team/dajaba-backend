package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** str 초안 요청용 공개 유스케이스 (SCRUM-155). 경보는 지울 수 없어 테스트마다 법인·계좌를 새로 만든다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class GetAlertForDraftTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final LocalDateTime REF = LocalDateTime.of(2022, 9, 3, 0, 0);

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
    GetAlertForDraft getAlertForDraft;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 경보_원문을_규약_2절_모양으로_돌려준다() {
        String entity = newEntity();
        long ruleRefId = rule(2, "FAN_IN");
        long alertId = alert(ruleRefId, entity, REF, """
                {"distinct_senders": 3, "currency": "US Dollar"}""");
        long first = transaction();
        long second = transaction();
        link(alertId, second);
        link(alertId, first);

        AlertPayload alert = getAlertForDraft.execute(alertId).alert();

        assertThat(alert.alertId()).isEqualTo(jdbc.queryForObject(
                "select alert_no from ops.alerts where id = ?", String.class, alertId));
        assertThat(alert.accountKey()).startsWith("10_A");
        assertThat(alert.entityId()).isEqualTo(entity);
        assertThat(alert.ruleId()).startsWith("R_TEST_");
        assertThat(alert.ruleVersion()).isEqualTo("2");
        assertThat(alert.typologyHint()).isEqualTo("FAN_IN");
        assertThat(alert.windowStart()).isEqualTo("2022-09-02T00:00:00");   // 초가 0이어도 잘리지 않는다
        assertThat(alert.windowEnd()).isEqualTo("2022-09-03T00:00:00");
        assertThat(alert.referenceTs()).isEqualTo("2022-09-03T00:00:00");
        assertThat(alert.transactionIds()).containsExactly(first, second);   // 거래 id 순
        assertThat(alert.ruleFacts().get("distinct_senders").asInt()).isEqualTo(3);
        assertThat(alert.createdAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void reasons는_점수_응답의_reasons_그대로() {
        long alertId = alert(rule(1, null), newEntity(), REF, "{}");
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, 0.9, 0.7, 'xgb-test', 'f1', ?, now(),
                        '{"reasons": [{"name": "account_in_counterparties_24h", "observation": {"value": 3}}]}'::jsonb)""",
                alertId, "p-" + alertId);

        AlertForDraft found = getAlertForDraft.execute(alertId);

        assertThat(found.reasons().get(0).get("name").asText()).isEqualTo("account_in_counterparties_24h");
        assertThat(found.reasons().get(0).get("observation").get("value").asInt()).isEqualTo(3);
    }

    @Test
    void 점수가_아직_없으면_reasons는_빈_배열() {   // AI 는 null 이면 422 (#64 리뷰)
        long alertId = alert(rule(1, null), newEntity(), REF, "{}");

        JsonNode reasons = getAlertForDraft.execute(alertId).reasons();
        assertThat(reasons.isArray()).isTrue();
        assertThat(reasons).isEmpty();
    }

    @Test
    void prior_alert_count는_같은_법인의_앞선_경보만_센다() {
        String entity = newEntity();
        long ruleRefId = rule(1, null);
        long target = alert(ruleRefId, entity, REF, "{}");
        alert(ruleRefId, entity, REF.minusDays(2), "{}");      // 앞섬 → 셈
        alert(ruleRefId, entity, REF.minusHours(1), "{}");     // 앞섬 → 셈
        alert(ruleRefId, entity, REF, "{}");                   // 같은 시각 → 안 셈
        alert(ruleRefId, entity, REF.plusDays(1), "{}");       // 뒤 → 안 셈
        alert(ruleRefId, newEntity(), REF.minusDays(2), "{}"); // 다른 법인 → 안 셈

        assertThat(getAlertForDraft.execute(target).priorAlertCount()).isEqualTo(2);
    }

    @Test
    void 과거_경보가_없거나_법인이_없으면_0() {
        long ruleRefId = rule(1, null);
        assertThat(getAlertForDraft.execute(alert(ruleRefId, newEntity(), REF, "{}")).priorAlertCount()).isZero();
        assertThat(getAlertForDraft.execute(alert(ruleRefId, null, REF, "{}")).priorAlertCount()).isZero();
    }

    @Test
    void 없는_경보는_404() {
        assertThatThrownBy(() -> getAlertForDraft.execute(Long.MAX_VALUE))
                .isInstanceOf(AlertNotFoundException.class);
    }

    private static String newEntity() {
        return "E_DRAFT_" + SEQUENCE.incrementAndGet();
    }

    private long rule(int version, String typologyHint) {
        return jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, ?, '테스트 룰', ?, '{}'::jsonb, 1, 1) returning id""",
                Long.class, "R_TEST_" + SEQUENCE.incrementAndGet(), version, typologyHint);
    }

    /** 창은 [기준 시각 - 1일, 기준 시각], 계좌는 매번 새로. */
    private long alert(long ruleRefId, String entityId, LocalDateTime referenceTs, String ruleFacts) {
        long n = SEQUENCE.incrementAndGet();
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, referenceTs.toLocalDate());
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""", Long.class,
                "ALT-DRAFT-" + n, batchRunId, ruleRefId, "10_A" + n, entityId, referenceTs.minusDays(1),
                referenceTs, referenceTs, ruleFacts, referenceTs.toLocalDate());
    }

    private long transaction() {
        long n = SEQUENCE.incrementAndGet();
        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('t.csv', ?) returning id",
                Long.class, String.format("%064d", n));
        return jdbc.queryForObject("""
                insert into ops.transactions(ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values ('2022-09-02 10:00', '10', 'A', '10_A', '20', 'B', '20_B', 1, 'US Dollar', 1, 'US Dollar',
                        'ACH', false, false, ?, 1, '010', '020') returning transaction_id""", Long.class, fileId);
    }

    private void link(long alertId, long transactionId) {
        jdbc.update("insert into ops.alert_transactions(alert_id, transaction_id) values (?, ?)", alertId, transactionId);
    }
}
