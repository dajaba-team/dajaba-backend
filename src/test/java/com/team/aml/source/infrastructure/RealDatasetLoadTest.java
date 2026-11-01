package com.team.aml.source.infrastructure;

import com.team.aml.source.application.LoadAccountFile;
import com.team.aml.source.application.LoadTransactionFile;
import com.team.aml.source.application.StagePatternFile;
import com.team.aml.source.domain.PatternMatchStats;
import com.team.aml.source.domain.PatternStagingRepository;
import com.team.aml.source.domain.TransactionLoadResult;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 규약 §1.5 검증값을 실제 파일 3개로 확인한다. 저장소에 파일이 없으므로 AML_DATASET_DIR을 지정한 PC에서만 돈다. */
@EnabledIfEnvironmentVariable(named = "AML_DATASET_DIR", matches = ".+")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RealDatasetLoadTest {

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
    StagePatternFile stagePatternFile;

    @Autowired
    LoadAccountFile loadAccountFile;

    @Autowired
    LoadTransactionFile loadTransactionFile;

    @Autowired
    PatternStagingRepository patterns;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 실제_파일_3개를_적재하면_규약_검증값이_나온다() {
        Path dir = Path.of(System.getenv("AML_DATASET_DIR"));
        Instant start = Instant.now();

        stagePatternFile.execute(dir.resolve("HI-Small_Patterns.txt"));
        loadAccountFile.execute(dir.resolve("HI-Small_accounts.csv"));
        TransactionLoadResult result = loadTransactionFile.execute(dir.resolve("HI-Small_Trans.csv"));

        System.out.println("REAL_LOAD_SECONDS=" + Duration.between(start, Instant.now()).toSeconds());
        System.out.println("REAL_AMOUNTS_ROUNDED=" + result.amountsRounded());

        assertThat(patterns.matchStats()).isEqualTo(new PatternMatchStats(3209, 0, 0));
        assertThat(result.fileRows()).isEqualTo(5_078_345);
        assertThat(result.insertedRows()).isEqualTo(5_078_345);
        assertThat(count("select count(*) from ops.transactions where is_laundering")).isEqualTo(5_177);
        assertThat(count("select count(*) from ops.transactions where is_self_transfer")).isEqualTo(591_212);
        assertThat(count("select count(*) from ops.transactions where pattern_type is not null")).isEqualTo(3_209);
        assertThat(count("select count(distinct pattern_id) from ops.transactions")).isEqualTo(370);
        assertThat(jdbc.queryForObject("select min(ts) from ops.transactions", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2022, 9, 1, 0, 0));
        assertThat(jdbc.queryForObject("select max(ts) from ops.transactions", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2022, 9, 18, 16, 18));
        assertThat(count("""
                select count(*) from ops.transactions t
                where not exists (select 1 from ops.accounts a where a.account_key = t.from_account_key)
                   or not exists (select 1 from ops.accounts a where a.account_key = t.to_account_key)"""))
                .isZero();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
