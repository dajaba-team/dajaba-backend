package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.detection.domain.RuleScanNotDoneException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.AlertPayload;
import com.team.aml.shared.ai.AiDtos.ScoreResult;
import com.team.aml.shared.ai.AiDtos.ScoresResponse;
import com.team.aml.shared.ai.AiDtos.ScoringMeta;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 점수 배치 SCORE_REQUEST (SCRUM-100) — 실제 PostgreSQL, AI 는 @MockBean. 묶음 크기를 2로 줄여 묶음 나눔을 본다.
 * 같은 날짜 RULE_SCAN 성공은 한 번뿐이라 테스트마다 날짜를 따로 쓴다. 경보는 V011 시드 룰(R02_FAN_IN 1)에 붙인다.
 */
@Testcontainers
@SpringBootTest(properties = "aml.ai.max-batch=2")
@ActiveProfiles("test")
class RequestScoresTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SYSTEM = CurrentUser.SYSTEM_ID;
    private static final ScoringMeta META = new ScoringMeta("xgb-test", "f1", "srv", 12);
    private static final String PRODUCED_AT = "2026-11-10T01:20:41Z";

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

    @Autowired RequestScores requestScores;
    @SpyBean ScoreBatchRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    @MockBean AiClient ai;

    /** 경보 번호 → 답. 없으면 OK. "FAILED:코드" 는 항목 실패, "NO_PREDICTION" 은 prediction_id 없는 OK. */
    private final Map<String, String> answers = new HashMap<>();
    /** AI 호출마다 보낸 경보 번호. */
    private final List<List<String>> sent = new ArrayList<>();
    /** 몇 번째 호출(1부터)을 연결 실패로 만들지. */
    private final Set<Integer> failingCalls = new HashSet<>();

    @BeforeEach
    void stubAi() {
        when(ai.scores(anyLong(), anyList())).thenAnswer(inv -> respond(inv.getArgument(1)));
    }

    @Test
    void 룰_스캔이_성공하지_않은_날은_409_RULE_SCAN_NOT_DONE이고_배치를_남기지_않는다() {
        LocalDate d = newDate();
        jdbc.update("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) values ('RULE_SCAN', ?, 'FAILED', 1)", d);

        assertThatThrownBy(() -> requestScores.start(d, SYSTEM)).isInstanceOf(RuleScanNotDoneException.class);
        assertThat(scoreRuns(d, null)).isZero();
    }

    @Test
    void 같은_날짜가_실행_중이면_409_BATCH_RUNNING() {
        LocalDate d = newDate();
        scanned(d);
        jdbc.update("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) values ('SCORE_REQUEST', ?, 'RUNNING', 1)", d);

        assertThatThrownBy(() -> requestScores.start(d, SYSTEM)).isInstanceOf(BatchRunningException.class);
        assertThat(scoreRuns(d, null)).isEqualTo(1);
    }

    @Test
    void 대상을_묶음_크기로_나눠_보내고_경보마다_alert_scores_한_행과_SCORED() {   // 11-1
        LocalDate d = newDate();
        long run = scanned(d);
        long a1 = alert(run, d), a2 = alert(run, d), a3 = alert(run, d);
        LocalDate other = newDate();
        alert(scanned(other), other);                 // 다른 날짜 경보는 보내지 않는다 (sent 에 없음)

        ScoreBatchResult r = runOnce(d);

        assertThat(sent).containsExactly(List.of(alertNo(a1), alertNo(a2)), List.of(alertNo(a3)));
        assertThat(r.summary()).isEqualTo(new ScoreBatchSummary(3, 3, 0, 0, 2, 0, List.of("xgb-test")));
        assertThat(r.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
        assertThat(r.run().rowsIn()).isEqualTo(3);
        assertThat(r.run().rowsOut()).isEqualTo(3);
        for (long a : new long[]{a1, a2, a3}) {
            assertThat(state(a)).isEqualTo("SCORED/-");
            assertThat(scoreRows(a)).isEqualTo(1);
        }
        Map<String, Object> s = jdbc.queryForMap("""
                select score, threshold, model_version, feature_version, prediction_id, payload::text as payload
                from ops.alert_scores where alert_id = ?""", a1);
        assertThat((BigDecimal) s.get("score")).isEqualByComparingTo("0.9");
        assertThat((BigDecimal) s.get("threshold")).isEqualByComparingTo("0.7");
        assertThat(s.get("model_version")).isEqualTo("xgb-test");
        assertThat(s.get("prediction_id")).isEqualTo("PRD-" + alertNo(a1));
        JsonNode payload = json((String) s.get("payload"));
        assertThat(payload.get("scoring_meta").get("model_version").asText()).isEqualTo("xgb-test");
        assertThat(payload.get("produced_at").asText()).isEqualTo(PRODUCED_AT);
        assertThat(payload.get("data_quality_status").asText()).isEqualTo("OK");
    }

    @Test
    void 보내는_경보는_규약_2절_모양이고_거래_ID는_id_순() {
        LocalDate d = newDate();
        long a = alert(scanned(d), d);
        List<AlertPayload> captured = new ArrayList<>();
        when(ai.scores(anyLong(), anyList())).thenAnswer(inv -> {
            captured.addAll(inv.getArgument(1));
            return respond(inv.getArgument(1));
        });

        runOnce(d);

        AlertPayload p = captured.get(0);
        assertThat(p.alertId()).isEqualTo(alertNo(a));
        assertThat(p.ruleId()).isEqualTo("R02_FAN_IN");
        assertThat(p.ruleVersion()).isEqualTo("1");
        assertThat(p.windowEnd()).isEqualTo(d + "T10:10:00");
        assertThat(p.transactionIds()).isSorted().hasSize(2);
        assertThat(p.createdAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void 항목_FAILED는_코드와_같이_남기고_행을_만들지_않는다_다시_누르면_COMPUTE_ERROR만_보낸다() {
        LocalDate d = newDate();
        long run = scanned(d);
        long ok = alert(run, d), notFound = alert(run, d), compute = alert(run, d);
        answers.put(alertNo(notFound), "FAILED:TRANSACTION_NOT_FOUND");
        answers.put(alertNo(compute), "FAILED:COMPUTE_ERROR");

        ScoreBatchResult first = runOnce(d);

        assertThat(first.summary()).isEqualTo(new ScoreBatchSummary(3, 1, 2, 1, 2, 0, List.of("xgb-test")));
        assertThat(first.run().status()).isEqualTo(BatchStatus.FAILED);   // 다시 보낼 COMPUTE_ERROR 가 남음
        assertThat(first.run().errorMessage()).contains("실패 2(다시 보낼 것 1)");
        assertThat(state(ok)).isEqualTo("SCORED/-");
        assertThat(state(notFound)).isEqualTo("FAILED/TRANSACTION_NOT_FOUND");
        assertThat(state(compute)).isEqualTo("FAILED/COMPUTE_ERROR");
        assertThat(scoreRows(notFound) + scoreRows(compute)).isZero();   // 0점·LOW 로 바꾸지 않음

        answers.clear();
        sent.clear();
        ScoreBatchResult second = runOnce(d);

        assertThat(sent).containsExactly(List.of(alertNo(compute)));   // SCORED·TRANSACTION_NOT_FOUND 는 안 보냄
        assertThat(second.summary()).isEqualTo(new ScoreBatchSummary(1, 1, 0, 0, 1, 0, List.of("xgb-test")));
        assertThat(second.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
        assertThat(state(compute)).isEqualTo("SCORED/-");
        assertThat(state(notFound)).isEqualTo("FAILED/TRANSACTION_NOT_FOUND");
    }

    @Test
    void 묶음_호출이_실패하면_그_묶음만_FAILED_코드없음_앞_묶음은_그대로() {   // 11-2
        LocalDate d = newDate();
        long run = scanned(d);
        long a1 = alert(run, d), a2 = alert(run, d), a3 = alert(run, d), a4 = alert(run, d);
        failingCalls.add(2);

        ScoreBatchResult r = runOnce(d);

        assertThat(r.summary()).isEqualTo(new ScoreBatchSummary(4, 2, 2, 2, 2, 1, List.of("xgb-test")));
        assertThat(r.run().status()).isEqualTo(BatchStatus.FAILED);
        assertThat(state(a1)).isEqualTo("SCORED/-");
        assertThat(state(a2)).isEqualTo("SCORED/-");
        assertThat(state(a3)).isEqualTo("FAILED/-");
        assertThat(state(a4)).isEqualTo("FAILED/-");
        assertThat(scoreRows(a3) + scoreRows(a4)).isZero();
    }

    @Test
    void AI가_돌아오면_다시_눌러_실패분만_SCORED_한_번_더_눌러도_행이_늘지_않는다() {   // 12-7
        LocalDate d = newDate();
        long run = scanned(d);
        long a1 = alert(run, d), a2 = alert(run, d), a3 = alert(run, d);
        failingCalls.add(1);
        runOnce(d);                                    // a1·a2 실패, a3 SCORED
        failingCalls.clear();
        sent.clear();

        ScoreBatchResult second = runOnce(d);

        assertThat(sent).containsExactly(List.of(alertNo(a1), alertNo(a2)));
        assertThat(second.summary()).isEqualTo(new ScoreBatchSummary(2, 2, 0, 0, 1, 0, List.of("xgb-test")));
        assertThat(second.run().status()).isEqualTo(BatchStatus.SUCCEEDED);

        sent.clear();
        ScoreBatchResult third = runOnce(d);           // 성공한 날도 다시 누를 수 있다 (V012)

        assertThat(sent).isEmpty();                    // 대상 0건 — AI 를 부르지 않음
        assertThat(third.summary()).isEqualTo(new ScoreBatchSummary(0, 0, 0, 0, 0, 0, List.of()));
        assertThat(third.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
        assertThat(scoreRows(a1) + scoreRows(a2) + scoreRows(a3)).isEqualTo(3);
        assertThat(scoreRuns(d, "SUCCEEDED")).isEqualTo(2);
    }

    @Test
    void 저장_칸이_빠진_항목은_그_경보만_FAILED_코드없음() {
        LocalDate d = newDate();
        long run = scanned(d);
        long good = alert(run, d), bad = alert(run, d);
        answers.put(alertNo(bad), "NO_PREDICTION");

        ScoreBatchResult r = runOnce(d);

        assertThat(state(good)).isEqualTo("SCORED/-");
        assertThat(state(bad)).isEqualTo("FAILED/-");
        assertThat(r.summary().retryableFailed()).isEqualTo(1);
    }

    @Test
    void 종료_기록이_실패해도_RUNNING으로_남지_않아_다시_돌릴_수_있다() {   // AI 리뷰 — finish 가 try 밖이면 409 BATCH_RUNNING 으로 막힘
        LocalDate d = newDate();
        long a = alert(scanned(d), d);
        doThrow(new IllegalStateException("db down")).when(recorder).finish(anyLong(), any());
        long id = requestScores.start(d, SYSTEM);

        assertThatThrownBy(() -> requestScores.run(id, d)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select status from ops.batch_runs where id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(state(a)).isEqualTo("SCORED/-");   // 점수는 묶음마다 이미 커밋돼 있다

        doCallRealMethod().when(recorder).finish(anyLong(), any());
        sent.clear();
        ScoreBatchResult again = runOnce(d);

        assertThat(sent).isEmpty();
        assertThat(again.run().status()).isEqualTo(BatchStatus.SUCCEEDED);
    }

    @Test
    void 앱_재시작으로_남은_RUNNING_점수_배치는_FAILED로() {
        LocalDate d = newDate();
        long id = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('SCORE_REQUEST', ?, 'RUNNING', 1) returning id", Long.class, d);

        recorder.failAbandonedScoreBatches();

        assertThat(jdbc.queryForObject("select status from ops.batch_runs where id = ?", String.class, id))
                .isEqualTo("FAILED");
    }

    @Test
    void V012_같은_날짜_SUCCEEDED는_점수_배치만_여러_번_된다() {
        LocalDate d = newDate();
        String insert = "insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) values (?, ?, 'SUCCEEDED', 1)";
        jdbc.update(insert, "SCORE_REQUEST", d);
        jdbc.update(insert, "SCORE_REQUEST", d);
        jdbc.update(insert, "RULE_SCAN", d);

        assertThatThrownBy(() -> jdbc.update(insert, "RULE_SCAN", d)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> {
            jdbc.update(insert, "OPEN_CASES", d);
            jdbc.update(insert, "OPEN_CASES", d);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- 준비 ------------------------------------------------------------------

    private ScoreBatchResult runOnce(LocalDate d) {
        return requestScores.run(requestScores.start(d, SYSTEM), d);
    }

    private ScoresResponse respond(List<AlertPayload> alerts) {
        sent.add(alerts.stream().map(AlertPayload::alertId).toList());
        if (failingCalls.contains(sent.size())) {
            throw new AiClientException("AI_UNAVAILABLE", "AI 서버 응답 없음");
        }
        List<ScoreResult> out = new ArrayList<>();
        for (AlertPayload a : alerts) {
            String answer = answers.getOrDefault(a.alertId(), "OK");
            ObjectNode raw = om.createObjectNode().put("alert_id", a.alertId());
            raw.putArray("reasons");
            if (answer.startsWith("FAILED:")) {
                raw.put("data_quality_status", "FAILED");
                out.add(new ScoreResult(a.alertId(), null, null, 0.7, "FAILED", answer.substring(7),
                        raw.get("reasons"), List.of(), List.of(), META, PRODUCED_AT, raw));
            } else {
                raw.put("data_quality_status", "OK");
                String predictionId = "NO_PREDICTION".equals(answer) ? null : "PRD-" + a.alertId();
                out.add(new ScoreResult(a.alertId(), predictionId, 0.9, 0.7, "OK", null,
                        raw.get("reasons"), a.transactionIds(), List.of(), META, PRODUCED_AT, raw));
            }
        }
        return new ScoresResponse(out);
    }

    private static LocalDate newDate() {
        return LocalDate.of(2030, 1, 1).plusDays(SEQUENCE.incrementAndGet());
    }

    /** 그 날짜 RULE_SCAN 성공 행 — 경보의 batch_run_id. */
    private long scanned(LocalDate d) {
        return jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', ?, 'SUCCEEDED', 1) returning id", Long.class, d);
    }

    /** V011 시드 룰에 경보 하나 + 거래 둘. 창은 [기준 - 96시간, 기준), 기준 = 그날 10:10. */
    private long alert(long batchRunId, LocalDate d) {
        long n = SEQUENCE.incrementAndGet();
        LocalDateTime ref = d.atTime(10, 10);
        long id = jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date)
                values (?, ?, (select id from ops.rules where rule_id = 'R02_FAN_IN' and version = 1), ?, null,
                        ?, ?, ?, '{"distinct_senders": 5}'::jsonb, ?) returning id""", Long.class,
                "ALT-SCORE-" + n, batchRunId, "10_S" + n, ref.minusHours(96), ref, ref, d);
        long t2 = transaction(), t1 = transaction();
        link(id, t2);
        link(id, t1);
        return id;
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

    private String alertNo(long alertId) {
        return jdbc.queryForObject("select alert_no from ops.alerts where id = ?", String.class, alertId);
    }

    /** "SCORED/-", "FAILED/COMPUTE_ERROR" 처럼. */
    private String state(long alertId) {
        return jdbc.queryForObject("select score_status || '/' || coalesce(score_failure_code, '-') from ops.alerts where id = ?",
                String.class, alertId);
    }

    private int scoreRows(long alertId) {
        return jdbc.queryForObject("select count(*) from ops.alert_scores where alert_id = ?", Integer.class, alertId);
    }

    private int scoreRuns(LocalDate d, String status) {
        return jdbc.queryForObject("select count(*) from ops.batch_runs where batch_type = 'SCORE_REQUEST' "
                + "and batch_date = ? and (?::text is null or status = ?)", Integer.class, d, status, status);
    }

    private JsonNode json(String text) {
        try {
            return om.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
