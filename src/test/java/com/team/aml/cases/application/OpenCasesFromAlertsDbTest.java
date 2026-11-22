package com.team.aml.cases.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.Test;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 케이스 생성 배치 OPEN_CASES (SCRUM-105). 경보마다 트랜잭션을 따로 잡는지 — 한 건 때문에 전부 되돌아가지 않는지 — 가 핵심.
 * 정책은 테스트용 값(0.5 / 0.9). 경보는 지울 수 없어 테스트마다 날짜를 바꾼다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.case-policy.version=test-v1",
        "aml.case-policy.low-to-medium=0.5",
        "aml.case-policy.medium-to-high=0.9"})
@ActiveProfiles("test")
class OpenCasesFromAlertsDbTest {

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

    @Autowired OpenCasesFromAlerts openCases;
    @Autowired OpenCasesRecorder recorder;
    @Autowired CaseRepository cases;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 앱_재시작으로_남은_RUNNING_케이스_생성만_FAILED로_끝난_행_다른_배치_처리_중인_행은_그대로() throws Exception {   // SCRUM-246
        LocalDate date = newDate();
        String insert = "insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) values (?, ?, ?, 1) returning id";
        long running = jdbc.queryForObject(insert, Long.class, "OPEN_CASES", date, "RUNNING");
        long done = jdbc.queryForObject(insert, Long.class, "OPEN_CASES", date, "SUCCEEDED");
        long otherType = jdbc.queryForObject(insert, Long.class, "SCORE_REQUEST", date, "RUNNING");
        long processing = jdbc.queryForObject(insert, Long.class, "OPEN_CASES", date.plusDays(1), "RUNNING");

        // 큐 리스너가 먼저 떠서 처리 중인 행 — 실행 번호 잠금을 다른 연결이 쥔 채로 정리를 돌린다 (#168 리뷰)
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        Future<?> holder = other.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(st -> {
            assertThat(cases.tryLockOpenCasesRun(processing)).isTrue();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            recorder.failAbandonedOpenCases();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            other.shutdown();
        }
        assertThat(statusOf(processing)).isEqualTo("RUNNING");   // 처리 중인 행은 그쪽이 결과를 남긴다
        jdbc.update("update ops.batch_runs set status = 'FAILED' where id = ?", processing);

        Map<String, Object> cleaned = jdbc.queryForMap(
                "select status, error_message, finished_at from ops.batch_runs where id = ?", running);
        assertThat(cleaned.get("status")).isEqualTo("FAILED");
        assertThat(cleaned.get("error_message")).isEqualTo("앱 재시작으로 중단된 케이스 생성");
        assertThat(cleaned.get("finished_at")).isNotNull();
        assertThat(statusOf(done)).isEqualTo("SUCCEEDED");
        assertThat(statusOf(otherType)).isEqualTo("RUNNING");   // 점수는 ScoreBatchRecorder 가 따로 정리
        assertThat(openCases.start(date, CurrentUser.SYSTEM_ID)).isPositive();   // 그 날짜가 다시 열림
        jdbc.update("update ops.batch_runs set status = 'FAILED' where id = ?", otherType);   // 다른 테스트에 남기지 않게
    }

    private String statusOf(long batchRunId) {
        return jdbc.queryForObject("select status from ops.batch_runs where id = ?", String.class, batchRunId);
    }

    @Test
    void 배치_잇기_시작은_그_시각_뒤_시작된_케이스_생성이_있으면_잠금_안에서_건너뛰고_행을_남기지_않는다() {   // SCRUM-349 리뷰
        LocalDate date = newDate();
        Instant before = Instant.now();
        jdbc.update("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by, started_at, finished_at) "
                + "values ('OPEN_CASES', ?, 'SUCCEEDED', 1, ?, ?)", date, Timestamp.from(before), Timestamp.from(before));   // 직접 실행이 그새 시작해 끝남 — 시각은 앱과 같은 JVM 시계로(DB now()는 컨테이너 시계라 어긋날 수 있음)

        assertThat(openCases.startUnlessStartedSince(date, CurrentUser.SYSTEM_ID, before)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ops.batch_runs where batch_type = 'OPEN_CASES' and batch_date = ?",
                Integer.class, date)).isEqualTo(1);

        assertThat(openCases.startUnlessStartedSince(date, CurrentUser.SYSTEM_ID, Instant.now().plusSeconds(60)))
                .isPresent();   // 그 시각 뒤엔 없음 — 새로 시작
    }

    @Test
    void 정상_HIGH만_케이스를_만들고_OPEN_이력과_경보_표시를_남기고_SUCCEEDED() {
        LocalDate date = newDate();
        long high = scoredAlert(date, "0.95");
        long boundary = scoredAlert(date, "0.9");   // 경계값과 같으면 HIGH (score ≥ medium_to_high)
        long medium = scoredAlert(date, "0.6");
        long low = scoredAlert(date, "0.1");

        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);
        OpenCasesSummary summary = openCases.run(runId, date).orElseThrow();

        assertThat(summary).isEqualTo(new OpenCasesSummary(4, 2, 2, 0, 0));
        assertThat(caseAlertIds(date)).containsExactlyInAnyOrder(high, boundary);
        assertThat(caseOpened(high)).isTrue();
        assertThat(caseOpened(medium)).isFalse();
        assertThat(caseOpened(low)).isFalse();

        Map<String, Object> c = jdbc.queryForMap(
                "select case_no, status, assignee_id, created_by from ops.cases where alert_id = ?", high);
        assertThat((String) c.get("case_no")).matches("CASE-2022-\\d{6,}");   // 경보 기준 시각(2022-09-02)의 연도
        assertThat(c.get("status")).isEqualTo("OPEN");
        assertThat(c.get("assignee_id")).isNull();
        assertThat(((Number) c.get("created_by")).longValue()).isEqualTo(CurrentUser.SYSTEM_ID);
        assertThat(jdbc.queryForList("""
                select h.action from ops.case_history h join ops.cases c on c.id = h.case_id
                where c.alert_id = ?""", String.class, high)).containsExactly("OPEN");

        assertThat(batchRun(runId)).containsEntry("status", "SUCCEEDED")
                .containsEntry("rows_in", 4).containsEntry("rows_out", 2);
    }

    @Test
    void 정상_케이스에_생성_당시_점수_모델_정책_스냅샷을_남긴다() {   // V013, SCRUM-236·237
        LocalDate date = newDate();
        long high = scoredAlert(date, "0.95");

        openCases.run(openCases.start(date, CurrentUser.SYSTEM_ID), date).orElseThrow();

        Map<String, Object> c = jdbc.queryForMap("""
                select opened_score_id, opened_score, opened_model_version, opened_feature_version,
                       opened_policy_version, opened_low_to_medium, opened_medium_to_high
                from ops.cases where alert_id = ?""", high);
        long scoreId = jdbc.queryForObject("select id from ops.alert_scores where alert_id = ?", Long.class, high);
        assertThat(((Number) c.get("opened_score_id")).longValue()).isEqualTo(scoreId);
        assertThat((BigDecimal) c.get("opened_score")).isEqualByComparingTo("0.95");
        assertThat(c).containsEntry("opened_model_version", "xgb-test").containsEntry("opened_feature_version", "f1")
                .containsEntry("opened_policy_version", "test-v1");
        assertThat((BigDecimal) c.get("opened_low_to_medium")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) c.get("opened_medium_to_high")).isEqualByComparingTo("0.9");
    }

    @Test
    void 경계_한_건이_실패해도_그_건만_롤백되고_나머지는_만들어지며_FAILED로_남는다() {
        LocalDate date = newDate();
        long first = scoredAlert(date, "0.95");
        long broken = brokenAlert(date);
        long last = scoredAlert(date, "0.97");

        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);
        OpenCasesSummary summary = openCases.run(runId, date).orElseThrow();

        assertThat(summary).isEqualTo(new OpenCasesSummary(3, 2, 0, 0, 1));
        assertThat(caseAlertIds(date)).containsExactlyInAnyOrder(first, last);   // 앞에서 만든 것도 살아 있다
        assertThat(caseOpened(broken)).isFalse();
        assertThat(batchRun(runId)).containsEntry("status", "FAILED");
        assertThat((String) batchRun(runId).get("error_message")).contains("생성 2").contains("실패 1");
    }

    @Test
    void 경계_FAILED인_날을_다시_돌리면_남은_경보만_다시_시도한다() {
        LocalDate date = newDate();
        long ok = scoredAlert(date, "0.95");
        long broken = brokenAlert(date);
        long firstRun = openCases.start(date, CurrentUser.SYSTEM_ID);
        openCases.run(firstRun, date);
        long later = scoredAlert(date, "0.99");   // 그 사이 점수 재시도로 새 HIGH 가 생겼다

        long secondRun = openCases.start(date, CurrentUser.SYSTEM_ID);   // FAILED 인 날은 다시 돌릴 수 있다
        OpenCasesSummary summary = openCases.run(secondRun, date).orElseThrow();

        // 이미 만든 ok 는 대상에 없고, broken 은 또 실패, later 는 새로 생성
        assertThat(summary).isEqualTo(new OpenCasesSummary(2, 1, 0, 0, 1));
        assertThat(caseAlertIds(date)).containsExactlyInAnyOrder(ok, later);
        assertThat(caseOpened(broken)).isFalse();
        assertThat(batchRun(secondRun)).containsEntry("status", "FAILED");
    }

    @Test
    void 경계_다른_실행이_먼저_만든_경보는_건너뛰고_나머지는_만든다() {
        LocalDate date = newDate();
        long taken = scoredAlert(date, "0.95");
        long free = scoredAlert(date, "0.96");
        // 다른 실행이 케이스는 넣었고 아직 표시 전인 순간 — cases.alert_id UNIQUE 에 걸린다
        jdbc.update("""
                insert into ops.cases(case_no, alert_id, account_key, status, created_by, updated_by)
                select 'CASE-2022-' || lpad(nextval('ops.case_no_seq')::text, 6, '0'), id, account_key, 'OPEN', 1, 1
                from ops.alerts where id = ?""", taken);

        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);
        OpenCasesSummary summary = openCases.run(runId, date).orElseThrow();

        assertThat(summary).isEqualTo(new OpenCasesSummary(2, 1, 0, 1, 0));
        assertThat(caseOpened(free)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from ops.cases where alert_id = ?", Integer.class, taken))
                .isEqualTo(1);
        assertThat(batchRun(runId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void 정상_대상이_없으면_0건으로_SUCCEEDED() {
        LocalDate date = newDate();

        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);

        assertThat(openCases.run(runId, date)).contains(new OpenCasesSummary(0, 0, 0, 0, 0));
        assertThat(batchRun(runId)).containsEntry("status", "SUCCEEDED").containsEntry("rows_out", 0);
    }

    @Test
    void 정상_성공한_날도_다시_돌리면_새로_HIGH가_된_경보만_케이스로_만들고_기존_케이스는_그대로() {   // SCRUM-238, V014
        LocalDate date = newDate();
        long first = scoredAlert(date, "0.95");
        long firstRun = openCases.start(date, CurrentUser.SYSTEM_ID);
        openCases.run(firstRun, date);
        assertThat(batchRun(firstRun)).containsEntry("status", "SUCCEEDED");

        long later = scoredAlert(date, "0.93");   // 첫 실행 뒤 점수가 매겨진 HIGH 경보
        long secondRun = openCases.start(date, CurrentUser.SYSTEM_ID);
        OpenCasesSummary again = openCases.run(secondRun, date).orElseThrow();

        assertThat(again).isEqualTo(new OpenCasesSummary(1, 1, 0, 0, 0));   // 대상은 아직 케이스 없는 경보뿐
        assertThat(caseAlertIds(date)).containsExactlyInAnyOrder(first, later);
        assertThat(jdbc.queryForObject("select count(*) from ops.cases where alert_id = ?", Integer.class, first))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from ops.batch_runs
                where batch_type = 'OPEN_CASES' and batch_date = ? and status = 'SUCCEEDED'""", Integer.class, date))
                .isEqualTo(2);
    }

    @Test
    void 실패_같은_날짜가_실행_중이면_409_BATCH_RUNNING() {
        LocalDate running = newDate();
        openCases.start(running, CurrentUser.SYSTEM_ID);   // run 전 = RUNNING
        assertThatThrownBy(() -> openCases.start(running, CurrentUser.SYSTEM_ID))
                .isInstanceOf(BatchRunningException.class);
        assertThat(jdbc.queryForObject("""
                select count(*) from ops.batch_runs where batch_type = 'OPEN_CASES' and batch_date = ?""",
                Integer.class, running)).isEqualTo(1);
    }

    // ── 도우미 ────────────────────────────────────────────────

    private List<Long> caseAlertIds(LocalDate date) {
        return jdbc.queryForList("""
                select c.alert_id from ops.cases c join ops.alerts a on a.id = c.alert_id
                where a.batch_date = ?""", Long.class, date);
    }

    private boolean caseOpened(long alertId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select case_opened from ops.alerts where id = ?", Boolean.class, alertId));
    }

    private Map<String, Object> batchRun(long id) {
        return jdbc.queryForMap("select status, rows_in, rows_out, error_message from ops.batch_runs where id = ?", id);
    }

    private static LocalDate newDate() {
        return LocalDate.of(2023, 1, 1).plusDays(SEQUENCE.incrementAndGet() * 3);
    }

    private long scoredAlert(LocalDate batchDate, String score) {
        return scoredAlert(batchDate, score, 2022);
    }

    /**
     * 케이스 INSERT 에서 진짜로 실패하는 HIGH 경보. 기준 연도가 999 라 케이스 번호가 CASE-999-… 가 되고
     * V006 cases_case_no_ck(연도 4자리)에 걸린다 — 가짜 객체 없이 "그 건만 롤백"을 본다. 케이스가 없는 제약 위반이라 실패로 센다.
     */
    private long brokenAlert(LocalDate batchDate) {
        return scoredAlert(batchDate, "0.96", 999);
    }

    /** CaseOpeningAlertsTest(SCRUM-108)와 같은 방식 — 룰 스캔·점수 배치 전이라 경보·점수를 직접 넣는다. */
    private long scoredAlert(LocalDate batchDate, String score, int year) {
        String y = String.format("%04d", year);
        long n = SEQUENCE.incrementAndGet();
        Long ruleId = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values (?, 1, '테스트 룰', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_OPEN_" + n);
        Long scanRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, batchDate);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date, score_status)
                values (?, ?, ?, ?, 'E1', ?::timestamp, ?::timestamp, ?::timestamp, '{}'::jsonb, ?, 'SCORED')
                returning id""", Long.class, "ALT-OPEN-" + n, scanRunId, ruleId, "10_B" + n,
                y + "-09-01 00:01", y + "-09-02 00:00", y + "-09-02 00:00", batchDate);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"data_quality_status": "OK"}'::jsonb)""",
                alertId, score, "p-" + alertId);
        return alertId;
    }
}
