package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.CaseAlreadyOpenedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** cases 케이스 생성 배치용 공개 유스케이스 두 개 (SCRUM-108). 경보는 지울 수 없어 테스트마다 날짜를 바꾼다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CaseOpeningAlertsTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

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
    FindAlertsForCaseOpening findAlertsForCaseOpening;

    @Autowired
    MarkCaseOpened markCaseOpened;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void 그_날짜의_점수_있고_케이스_없는_경보만_점수와_함께_돌려준다() {
        LocalDate date = newDate();
        long low = scoredAlert(date, "0.120000001");
        long high = scoredAlert(date, "0.95");
        alert(date, "PENDING");
        alert(date, "FAILED");
        long opened = scoredAlert(date, "0.99");
        jdbc.update("update ops.alerts set case_opened = true where id = ?", opened);
        scoredAlert(date.plusDays(1), "0.99");

        assertThat(findAlertsForCaseOpening.execute(date))
                .extracting(AlertForCaseOpening::alertId)
                .containsExactly(low, high);   // HIGH 로 미리 거르지 않는다 — 등급은 CasePolicy (안건 2)
    }

    @Test
    void 점수_칸은_AI가_준_값을_그대로_옮긴다() {
        LocalDate date = newDate();
        long alertId = scoredAlert(date, "0.912345678");

        AlertForCaseOpening found = findAlertsForCaseOpening.execute(date).get(0);

        assertThat(found.alertId()).isEqualTo(alertId);
        assertThat(found.accountKey()).startsWith("10_A");
        assertThat(found.entityId()).isEqualTo("E1");
        assertThat(found.referenceTs()).isEqualTo(LocalDateTime.of(2022, 9, 2, 0, 0));
        assertThat(found.score()).isEqualByComparingTo("0.912345678");
        assertThat(found.recommendedThreshold()).isEqualByComparingTo("0.7");
        assertThat(found.modelVersion()).isEqualTo("xgb-test");
        assertThat(found.featureVersion()).isEqualTo("f1");
        assertThat(found.dataQualityStatus()).isEqualTo("SHORT_HISTORY");
        assertThat(found.predictionId()).isEqualTo("p-" + alertId);
        assertThat(found.scoreId()).isEqualTo(
                jdbc.queryForObject("select id from ops.alert_scores where alert_id = ?", Long.class, alertId));
    }

    @Test
    void 대상이_없으면_빈_목록() {
        assertThat(findAlertsForCaseOpening.execute(newDate())).isEmpty();
    }

    @Test
    void 케이스_생성_트랜잭션_안에서_표시하면_대상에서_빠진다() {
        LocalDate date = newDate();
        long alertId = scoredAlert(date, "0.9");

        inTx(() -> markCaseOpened.execute(alertId));

        assertThat(caseOpened(alertId)).isTrue();
        assertThat(findAlertsForCaseOpening.execute(date)).isEmpty();
    }

    @Test
    void 이미_표시된_경보는_409() {
        long alertId = scoredAlert(newDate(), "0.9");
        inTx(() -> markCaseOpened.execute(alertId));

        assertThatThrownBy(() -> inTx(() -> markCaseOpened.execute(alertId)))
                .isInstanceOf(CaseAlreadyOpenedException.class);
    }

    @Test
    void 없는_경보는_404() {
        assertThatThrownBy(() -> inTx(() -> markCaseOpened.execute(Long.MAX_VALUE)))
                .isInstanceOf(AlertNotFoundException.class);
    }

    @Test
    void 케이스_생성이_롤백되면_표시도_롤백된다() {
        long alertId = scoredAlert(newDate(), "0.9");

        assertThatThrownBy(() -> inTx(() -> {
            markCaseOpened.execute(alertId);
            throw new IllegalStateException("케이스 INSERT 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(caseOpened(alertId)).isFalse();
    }

    @Test
    void 트랜잭션_밖에서_부르면_바로_실패하고_바뀌지_않는다() {
        long alertId = scoredAlert(newDate(), "0.9");

        assertThatThrownBy(() -> markCaseOpened.execute(alertId))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(caseOpened(alertId)).isFalse();
    }

    private void inTx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private boolean caseOpened(long alertId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select case_opened from ops.alerts where id = ?", Boolean.class, alertId));
    }

    private static LocalDate newDate() {
        return LocalDate.of(2022, 1, 1).plusDays(SEQUENCE.incrementAndGet() * 3);
    }

    private long scoredAlert(LocalDate batchDate, String score) {
        long alertId = alert(batchDate, "SCORED");
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"data_quality_status": "SHORT_HISTORY"}'::jsonb)""",
                alertId, score, "p-" + alertId);
        return alertId;
    }

    private long alert(LocalDate batchDate, String scoreStatus) {
        long n = SEQUENCE.incrementAndGet();
        Long ruleId = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values (?, 1, '테스트 룰', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_TEST_" + n);
        Long batchRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, batchDate);
        return jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date, score_status)
                values (?, ?, ?, ?, 'E1', '2022-09-01 00:01', '2022-09-02 00:00', '2022-09-02 00:00', '{}'::jsonb, ?, ?)
                returning id""", Long.class,
                "ALT-TEST-" + n, batchRunId, ruleId, "10_A" + n, batchDate, scoreStatus);
    }
}
