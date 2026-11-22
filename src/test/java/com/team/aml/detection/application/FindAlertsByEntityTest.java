package com.team.aml.detection.application;

import com.team.aml.detection.domain.PriorAlert;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 법인의 이전 경보 공개 조회 (SCRUM-239). 경보는 지울 수 없어 테스트마다 법인·계좌를 새로 만든다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class FindAlertsByEntityTest {

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
    FindAlertsByEntity findAlertsByEntity;

    @Autowired
    GetAlertForDraft getAlertForDraft;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 정상_같은_법인의_앞선_경보만_최근_것부터_건수는_prior_alert_count와_같다() {
        String entity = newEntity();
        long ruleRefId = rule(1, null);
        long target = alert(ruleRefId, entity, REF, "{}");
        long twoDaysAgo = alert(ruleRefId, entity, REF.minusDays(2), "{}");   // 앞섬
        long hourAgo = alert(ruleRefId, entity, REF.minusHours(1), "{}");     // 앞섬
        alert(ruleRefId, entity, REF, "{}");                                  // 같은 시각 → 빠짐
        alert(ruleRefId, entity, REF.plusDays(1), "{}");                      // 뒤 → 빠짐
        alert(ruleRefId, newEntity(), REF.minusDays(2), "{}");                // 다른 법인 → 빠짐
        score(hourAgo, "0.91");

        List<PriorAlert> prior = findAlertsByEntity.execute(entity, REF, 20);

        assertThat(prior).extracting(PriorAlert::alertId).containsExactly(hourAgo, twoDaysAgo);
        assertThat(prior).hasSize(getAlertForDraft.execute(target).priorAlertCount());
        PriorAlert first = prior.get(0);
        assertThat(first.alertNo()).startsWith("ALT-DRAFT-");
        assertThat(first.ruleId()).startsWith("R_TEST_");
        assertThat(first.batchDate()).isEqualTo(REF.minusHours(1).toLocalDate());   // 배치 날짜 = 기준 시각의 날짜
        assertThat(first.referenceTs()).isEqualTo(REF.minusHours(1));
        assertThat(first.accountKey()).startsWith("10_A");
        assertThat(first.scoreStatus()).isEqualTo("SCORED");
        assertThat(first.score()).isEqualByComparingTo(new BigDecimal("0.91"));
        assertThat(prior.get(1).scoreStatus()).isEqualTo("PENDING");
        assertThat(prior.get(1).score()).isNull();
    }

    @Test
    void 경계_limit만큼_최근_것만_같은_시각이면_id_큰_것부터() {
        String entity = newEntity();
        long ruleRefId = rule(1, null);
        long older = alert(ruleRefId, entity, REF.minusDays(3), "{}");
        long sameA = alert(ruleRefId, entity, REF.minusDays(1), "{}");
        long sameB = alert(ruleRefId, entity, REF.minusDays(1), "{}");

        assertThat(findAlertsByEntity.execute(entity, REF, 2)).extracting(PriorAlert::alertId)
                .containsExactly(sameB, sameA);
        assertThat(findAlertsByEntity.execute(entity, REF, 20)).extracting(PriorAlert::alertId)
                .containsExactly(sameB, sameA, older);
    }

    @Test
    void 법인이_없거나_기준_시각이_없으면_빈_목록이고_계좌로_잇지_않는다() {
        long ruleRefId = rule(1, null);
        alert(ruleRefId, null, REF.minusDays(1), "{}");

        assertThat(findAlertsByEntity.execute(null, REF, 20)).isEmpty();
        assertThat(findAlertsByEntity.execute(newEntity(), null, 20)).isEmpty();
        assertThat(findAlertsByEntity.execute(newEntity(), REF, 20)).isEmpty();
    }

    @Test
    void 실패_limit이_1보다_작으면_IllegalArgumentException() {
        assertThatThrownBy(() -> findAlertsByEntity.execute(newEntity(), REF, 0))
                .isInstanceOf(IllegalArgumentException.class);
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

    private void score(long alertId, String score) {
        jdbc.update("update ops.alerts set score_status = 'SCORED' where id = ?", alertId);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"reasons": []}'::jsonb)""",
                alertId, score, "p-prior-" + alertId);
    }
}
