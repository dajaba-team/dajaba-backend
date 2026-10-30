package com.team.aml;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "빈 테스트 1개": Testcontainers PostgreSQL 16 + Flyway V001~V004 + V007 + ddl-auto=validate 로 컨텍스트가 뜨는지.
 * 이게 통과하면 엔티티와 테이블이 맞는 것이다. Docker 가 있어야 돈다 (H2 는 쓰지 않는다 — 레퍼런스 6.3).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

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

    @Test
    void contextLoads() {
        assertThat(db.isRunning()).isTrue();
    }
}
