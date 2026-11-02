package com.team.aml.source.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 파일 3개를 LoadIbmDataset 한 트랜잭션으로 적재한다. AML_DATASET_DIR을 지정한 PC에서만 돈다. */
@EnabledIfEnvironmentVariable(named = "AML_DATASET_DIR", matches = ".+")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RealIbmDatasetTest {

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

    @DynamicPropertySource
    static void datasetDir(DynamicPropertyRegistry registry) {
        registry.add("aml.dataset-dir", () -> System.getenv("AML_DATASET_DIR"));
    }

    @Autowired
    LoadIbmDataset loadIbmDataset;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 실제_파일을_한_트랜잭션으로_적재하고_검증을_통과한다() {
        Instant start = Instant.now();
        long batchRunId = loadIbmDataset.start(1L);
        loadIbmDataset.run(batchRunId);
        System.out.println("REAL_IBM_DATASET_SECONDS=" + Duration.between(start, Instant.now()).toSeconds());

        Map<String, Object> run = jdbc.queryForMap(
                "select status, rows_in, rows_out, error_message from ops.batch_runs where id = ?", batchRunId);
        assertThat(run)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("rows_in", 5_078_345)
                .containsEntry("rows_out", 5_078_345)
                .containsEntry("error_message", null);
    }
}
