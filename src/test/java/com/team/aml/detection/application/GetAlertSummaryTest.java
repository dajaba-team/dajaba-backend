package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.AlertSummary;
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

/** 케이스 상세용 공개 유스케이스 (SCRUM-152). 경보는 지울 수 없어 테스트마다 계좌를 새로 만든다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class GetAlertSummaryTest {

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
    GetAlertSummary getAlertSummary;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 경보_칸과_거래_수() {
        long alertId = alert(rule(), "PENDING", null);
        link(alertId, transaction());
        link(alertId, transaction());

        AlertSummary s = getAlertSummary.execute(alertId);

        assertThat(s.alertNo()).startsWith("ALT-SUM-");
        assertThat(s.ruleId()).startsWith("R_TEST_");
        assertThat(s.batchDate()).isEqualTo(REF.toLocalDate());
        assertThat(s.windowStart()).isEqualTo(REF.minusDays(1));
        assertThat(s.windowEnd()).isEqualTo(REF);
        assertThat(s.referenceTs()).isEqualTo(REF);
        assertThat(s.transactionCount()).isEqualTo(2);
    }

    @Test
    void 점수가_있으면_점수_칸과_reasons_그대로() {
        long alertId = alert(rule(), "SCORED", null);
        score(alertId, """
                {"data_quality_status": "OK", "reasons": [{"name": "account_in_counterparties_24h"}]}""");

        AlertSummary.Score s = getAlertSummary.execute(alertId).score();

        assertThat(s.status()).isEqualTo("SCORED");
        assertThat(s.score()).isEqualByComparingTo("0.91");
        assertThat(s.failureCode()).isNull();
        assertThat(s.modelVersion()).isEqualTo("xgb-test");
        assertThat(s.featureVersion()).isEqualTo("f1");
        assertThat(s.dataQualityStatus()).isEqualTo("OK");
        assertThat(s.reasons().get(0).get("name").asText()).isEqualTo("account_in_counterparties_24h");
    }

    @Test
    void 점수는_있는데_설명이_비면_점수는_두고_reasons는_빈_배열() {   // 설명 실패 (계약서 C2, 준영 11/3)
        long alertId = alert(rule(), "SCORED", null);
        score(alertId, """
                {"data_quality_status": "OK", "reasons": []}""");

        AlertSummary.Score s = getAlertSummary.execute(alertId).score();

        assertThat(s.score()).isNotNull();
        assertThat(s.reasons()).isEmpty();
    }

    @Test
    void 점수_전이면_점수_칸은_null이고_reasons는_빈_배열() {
        AlertSummary.Score s = getAlertSummary.execute(alert(rule(), "PENDING", null)).score();

        assertThat(s.status()).isEqualTo("PENDING");
        assertThat(s.score()).isNull();
        assertThat(s.modelVersion()).isNull();
        assertThat(s.dataQualityStatus()).isNull();
        assertThat(s.reasons().isArray()).isTrue();
        assertThat(s.reasons()).isEmpty();
    }

    @Test
    void 산출_실패면_실패_코드() {   // 0점·저위험이 아니라 "산출 실패" (계약서 5.6)
        AlertSummary.Score s = getAlertSummary.execute(
                alert(rule(), "FAILED", "HISTORY_NOT_SUPPORTED")).score();

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.failureCode()).isEqualTo("HISTORY_NOT_SUPPORTED");
        assertThat(s.score()).isNull();
    }

    @Test
    void 묶음_실패면_FAILED인데_실패_코드는_null() {   // 응답 누락·묶음 호출 실패 (미결 16) — 재처리 대상
        AlertSummary.Score s = getAlertSummary.execute(alert(rule(), "FAILED", null)).score();

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.failureCode()).isNull();
        assertThat(s.score()).isNull();
        assertThat(s.reasons()).isEmpty();
    }

    @Test
    void 없는_경보는_404() {
        assertThatThrownBy(() -> getAlertSummary.execute(Long.MAX_VALUE))
                .isInstanceOf(AlertNotFoundException.class);
    }

    private void score(long alertId, String payload) {
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, 0.91, 0.7, 'xgb-test', 'f1', ?, now(), ?::jsonb)""", alertId, "p-" + alertId, payload);
    }

    private long rule() {
        return jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values (?, 1, '테스트 룰', '{}'::jsonb, 1, 1) returning id""",
                Long.class, "R_TEST_" + SEQUENCE.incrementAndGet());
    }

    /** 창은 [기준 시각 - 1일, 기준 시각], 계좌는 매번 새로. */
    private long alert(long ruleRefId, String scoreStatus, String failureCode) {
        long n = SEQUENCE.incrementAndGet();
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, REF.toLocalDate());
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                       reference_ts, rule_facts, batch_date, score_status, score_failure_code)
                values (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?) returning id""", Long.class,
                "ALT-SUM-" + n, batchRunId, ruleRefId, "10_S" + n, REF.minusDays(1), REF, REF,
                REF.toLocalDate(), scoreStatus, failureCode);
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
