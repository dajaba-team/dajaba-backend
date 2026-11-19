package com.team.aml.cases.api;

import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** RabbitMQ 를 켰는데 계정이 틀려도 앱이 뜨고, 보내기는 false(동기 경로) — fail-open (SCRUM-276). */
@Testcontainers
@SpringBootTest(properties = "aml.messaging.enabled=true")
@ActiveProfiles("test")
class RabbitAuthFailOpenDbTest {

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
        r.add("spring.rabbitmq.password", () -> "wrong-password");
    }

    @Autowired JobPublisher publisher;

    @Test
    void 경계_계정이_틀려도_앱은_뜨고_보내기는_false() {
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(1L))).isFalse();
    }
}
