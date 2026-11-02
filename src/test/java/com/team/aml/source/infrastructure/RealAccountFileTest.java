package com.team.aml.source.infrastructure;

import com.team.aml.source.application.LoadAccountFile;
import com.team.aml.source.domain.AccountLoadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 파일은 저장소에 없으므로 AML_DATASET_DIR을 지정한 PC에서만 돈다. CI는 건너뛴다. */
@EnabledIfEnvironmentVariable(named = "AML_DATASET_DIR", matches = ".+")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RealAccountFileTest {

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
    LoadAccountFile loadAccountFile;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 실제_파일은_계좌_518581_법인_166207_국가_은행_18642다() {
        AccountLoadResult result = loadAccountFile.execute(
                Path.of(System.getenv("AML_DATASET_DIR"), "HI-Small_accounts.csv"));

        assertThat(result).isEqualTo(new AccountLoadResult(518_581, 166_207, 518_581, 0));
        assertThat(jdbc.queryForObject(
                "select count(distinct bank_id) from ops.accounts where bank_country is not null", Long.class))
                .isEqualTo(18_642);
        assertThat(jdbc.queryForObject(
                "select count(distinct bank_country) from ops.accounts", Long.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject(
                "select count(*) from ops.entities where entity_type is null", Long.class)).isZero();
    }
}
