package com.team.aml.cases.application;

import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.security.CurrentUser;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    @Autowired JdbcTemplate jdbc;

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
    void 실패_성공한_날은_409_BATCH_ALREADY_DONE_실행_중이면_409_BATCH_RUNNING() {
        LocalDate done = newDate();
        openCases.run(openCases.start(done, CurrentUser.SYSTEM_ID), done);
        assertThatThrownBy(() -> openCases.start(done, CurrentUser.SYSTEM_ID))
                .isInstanceOf(BatchAlreadyDoneException.class);

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
