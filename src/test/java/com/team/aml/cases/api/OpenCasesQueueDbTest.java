package com.team.aml.cases.api;

import com.team.aml.cases.application.OpenCasesFromAlerts;
import com.team.aml.cases.application.RunOpenCasesOnce;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import com.team.aml.shared.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * 케이스 생성 단계를 진짜 RabbitMQ 로 (SCRUM-276). compose 와 같은 이미지·vhost {@code aml}.
 * 큐 수신 → 기존 생성 로직 실행 → batch_runs SUCCEEDED, 같은 메시지 두 번에도 케이스 1건, 형식이 틀린 메시지는 DLQ.
 * #130 리뷰: 다른 곳이 실행 잠금을 쥐고 있으면 건너뜀(이중 실행 없음), 재시도를 다 쓰면 행을 FAILED 로 바꾸고 DLQ.
 */
@Testcontainers
@SpringBootTest(properties = {
        "aml.messaging.enabled=true",
        "aml.case-policy.version=test-v1",
        "aml.case-policy.low-to-medium=0.5",
        "aml.case-policy.medium-to-high=0.9"})
@ActiveProfiles("test")
class OpenCasesQueueDbTest {

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

    @Container
    // compose 와 같은 환경변수로 띄운다 (RabbitMQContainer 는 계정을 guest 로 덮어써서 일반 컨테이너로)
    static GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:4.4.0-management-alpine")
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "aml")
            .withEnv("RABBITMQ_DEFAULT_PASS", "aml-test")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "aml")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "aml");
        r.add("spring.rabbitmq.password", () -> "aml-test");
    }

    @Autowired OpenCasesFromAlerts openCases;
    @Autowired JobPublisher publisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @SpyBean RunOpenCasesOnce runOnce;

    @Test
    void 정상_메시지를_받으면_기존_생성_로직으로_케이스를_만들고_SUCCEEDED() {
        LocalDate date = newDate();
        long high = scoredAlert(date, "0.95");
        scoredAlert(date, "0.1");
        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);

        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(runId))).isTrue();

        await(() -> "SUCCEEDED".equals(status(runId)));
        assertThat(jdbc.queryForObject("select count(*) from ops.cases where alert_id = ?", Integer.class, high))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select rows_out from ops.batch_runs where id = ?", Integer.class, runId))
                .isEqualTo(1);
    }

    @Test
    void 경계_같은_메시지가_두_번_와도_케이스는_한_건() {
        LocalDate date = newDate();
        long high = scoredAlert(date, "0.95");
        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);

        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(runId))).isTrue();
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(runId))).isTrue();

        await(() -> "SUCCEEDED".equals(status(runId)));
        await(() -> queueDepth(JobQueues.CASE_OPEN_CASES) == 0);
        assertThat(jdbc.queryForObject("select count(*) from ops.cases where alert_id = ?", Integer.class, high))
                .isEqualTo(1);
    }

    @Test
    void 실패_형식이_틀린_메시지는_재시도_없이_DLQ로() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(JobQueues.EXCHANGE, JobQueues.CASE_OPEN_CASES,
                new Message("not-json".getBytes(StandardCharsets.UTF_8), props));

        assertThat(receiveDead("not-json")).isTrue();
    }

    @Test
    void 경계_다른_곳이_같은_실행을_잡고_있으면_리스너는_건너뛰고_ACK() throws Exception {
        LocalDate date = newDate();
        long high = scoredAlert(date, "0.95");
        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> tx.executeWithoutResult(s -> {
            // 앱 안 실행기가 먼저 잡고 도는 중인 상황 (확인이 늦어 두 경로가 다 움직인 경우)
            jdbc.queryForObject("select pg_try_advisory_xact_lock(6006, (? % 2147483647)::int)", Boolean.class, runId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(runId))).isTrue();

            await(() -> queueDepth(JobQueues.CASE_OPEN_CASES) == 0);
            Thread.sleep(500);   // 리스너가 ACK 한 뒤
            assertThat(status(runId)).isEqualTo("RUNNING");   // 잡은 쪽이 끝낼 몫 — 덮지 않음
            assertThat(jdbc.queryForObject("select count(*) from ops.cases where alert_id = ?", Integer.class, high))
                    .isZero();
        } finally {
            release.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void 실패_재시도를_다_쓰면_행을_FAILED로_바꿔_같은_날짜를_다시_요청할_수_있게_하고_DLQ로() {
        LocalDate date = newDate();
        long runId = openCases.start(date, CurrentUser.SYSTEM_ID);
        doThrow(new DataAccessResourceFailureException("db down")).when(runOnce).execute(runId);

        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(runId))).isTrue();

        await(() -> "FAILED".equals(status(runId)));
        assertThat(jdbc.queryForObject("select error_message from ops.batch_runs where id = ?", String.class, runId))
                .contains("재시도");
        assertThat(receiveDead("\"batch_run_id\":" + runId)).isTrue();
        long again = openCases.start(date, CurrentUser.SYSTEM_ID);   // 409 BATCH_RUNNING 으로 막히지 않음
        assertThat(again).isNotEqualTo(runId);
    }

    /** DLQ 에서 본문에 text 가 든 메시지가 15초 안에 나오나. 다른 테스트의 메시지는 버린다. */
    private boolean receiveDead(String text) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Message dead = rabbitTemplate.receive(JobQueues.deadLetterQueue(JobQueues.CASE_OPEN_CASES), 1_000);
            if (dead != null && new String(dead.getBody(), StandardCharsets.UTF_8).contains(text)) {
                return true;
            }
        }
        return false;
    }

    private Integer queueDepth(String queue) {
        var info = rabbitTemplate.execute(ch -> ch.queueDeclarePassive(queue));
        return info.getMessageCount();
    }

    private String status(long runId) {
        return jdbc.queryForObject("select status from ops.batch_runs where id = ?", String.class, runId);
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("20초 안에 조건이 안 맞음");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static LocalDate newDate() {
        return LocalDate.of(2024, 1, 1).plusDays(SEQUENCE.incrementAndGet() * 3);
    }

    private long scoredAlert(LocalDate batchDate, String score) {
        long n = SEQUENCE.incrementAndGet();
        Long ruleId = jdbc.queryForObject("""
                insert into ops.rules(rule_id, version, name, params, created_by, updated_by)
                values (?, 1, '테스트 룰', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_QUEUE_" + n);
        Long scanRunId = jdbc.queryForObject("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, batchDate);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                       window_end, reference_ts, rule_facts, batch_date, score_status)
                values (?, ?, ?, ?, 'E1', ?::timestamp, ?::timestamp, ?::timestamp, '{}'::jsonb, ?, 'SCORED')
                returning id""", Long.class, "ALT-QUEUE-" + n, scanRunId, ruleId, "10_Q" + n,
                "2022-09-01 00:01", "2022-09-02 00:00", "2022-09-02 00:00", batchDate);
        jdbc.update("""
                insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                             prediction_id, produced_at, payload)
                values (?, ?::numeric, 0.7, 'xgb-test', 'f1', ?, now(), '{"data_quality_status": "OK"}'::jsonb)""",
                alertId, score, "p-" + alertId);
        return alertId;
    }
}
