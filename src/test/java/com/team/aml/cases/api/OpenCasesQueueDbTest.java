package com.team.aml.cases.api;

import com.team.aml.cases.application.OpenCasesFromAlerts;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 케이스 생성 단계를 진짜 RabbitMQ 로 (SCRUM-276). compose 와 같은 이미지·vhost {@code aml}.
 * 큐 수신 → 기존 생성 로직 실행 → batch_runs SUCCEEDED, 같은 메시지 두 번에도 케이스 1건, 형식이 틀린 메시지는 DLQ.
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
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "aml")
            .withEnv("RABBITMQ_DEFAULT_PASS", "aml-test")
            .withEnv("RABBITMQ_DEFAULT_VHOST", "aml");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username", () -> "aml");
        r.add("spring.rabbitmq.password", () -> "aml-test");
    }

    @Autowired OpenCasesFromAlerts openCases;
    @Autowired JobPublisher publisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbc;

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

        Message dead = rabbitTemplate.receive(JobQueues.deadLetterQueue(JobQueues.CASE_OPEN_CASES), 15_000);
        assertThat(dead).isNotNull();
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
