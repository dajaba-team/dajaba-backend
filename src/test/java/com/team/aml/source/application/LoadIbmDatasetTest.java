package com.team.aml.source.application;

import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.source.domain.DatasetExpectation;
import com.team.aml.source.domain.DatasetFiles;
import com.team.aml.source.domain.DatasetFilesMissingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** 파일 3개 적재 → 검증 → batch_runs 기록, 실패 시 롤백 (SCRUM-69). */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class LoadIbmDatasetTest {

    private static final Path DIR = tempDir();

    private static final String TRANS_HEADER = "Timestamp,From Bank,Account,To Bank,Account,Amount Received,"
            + "Receiving Currency,Amount Paid,Payment Currency,Payment Format,Is Laundering";
    private static final String LAUNDERING_ROW = "2022/09/01 00:20,010,A1,020,B1,100.00,US Dollar,100.00,US Dollar,ACH,1";
    private static final String SELF_ROW = "2022/09/01 00:21,010,A1,010,A1,5.00,US Dollar,5.00,US Dollar,ACH,0";

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
        registry.add("aml.dataset-dir", DIR::toString);
    }

    /** 아래 작은 파일에 맞춘 기대값. */
    @TestConfiguration
    static class SmallDataset {
        @Bean
        @Primary
        DatasetExpectation smallExpectation() {
            return new DatasetExpectation(2, 1, 1, 1, 1,
                    LocalDateTime.of(2022, 9, 1, 0, 20), LocalDateTime.of(2022, 9, 1, 0, 21), 2, 2);
        }
    }

    @Autowired
    LoadIbmDataset loadIbmDataset;

    @SpyBean
    DatasetLoadRecorder recorder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws IOException {
        jdbc.execute("truncate table ops.transactions_staging, ops.patterns_staging, ops.accounts_staging");
        jdbc.update("delete from ops.transactions");
        jdbc.update("delete from ops.source_files");
        jdbc.update("delete from ops.accounts");
        jdbc.update("delete from ops.entities");
        jdbc.update("delete from ops.batch_runs");
        writeFiles(LAUNDERING_ROW, SELF_ROW);
    }

    @Test
    void 파일_3개를_적재하고_성공을_기록한다() {
        long batchRunId = loadIbmDataset.start(1L);
        loadIbmDataset.run(batchRunId);

        assertThat(batchRun(batchRunId))
                .containsEntry("batch_type", "LOAD_DATASET")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("rows_in", 2)
                .containsEntry("rows_out", 2)
                .containsEntry("error_message", null);
        assertThat(count("select count(*) from ops.transactions")).isEqualTo(2);
        assertThat(count("select count(*) from ops.transactions where pattern_id is not null")).isEqualTo(1);
        assertThat(count("select count(*) from ops.accounts")).isEqualTo(2);
        assertThat(count("select count(*) from ops.transactions_staging")
                + count("select count(*) from ops.patterns_staging")
                + count("select count(*) from ops.accounts_staging")).isZero();
    }

    @Test
    void 검증값이_다르면_전부_롤백하고_실패를_기록한다() throws IOException {
        writeFiles(LAUNDERING_ROW, SELF_ROW, "2022/09/01 00:22,020,B1,010,A1,7.00,US Dollar,7.00,US Dollar,ACH,0");

        long batchRunId = loadIbmDataset.start(1L);
        loadIbmDataset.run(batchRunId);

        assertThat(batchRun(batchRunId)).containsEntry("status", "FAILED");
        assertThat((String) batchRun(batchRunId).get("error_message"))
                .startsWith("검증값 불일치")
                .contains("거래 수 3 (기대 2)")
                .doesNotContain("B1");
        assertThat(count("select count(*) from ops.transactions")).isZero();
        assertThat(count("select count(*) from ops.source_files")).isZero();
        assertThat(count("select count(*) from ops.accounts")).isZero();
        assertThat(count("select count(*) from ops.entities")).isZero();
    }

    @Test
    void 파일_형식이_틀리면_롤백하고_이유만_기록한다() throws IOException {
        writeFiles(LAUNDERING_ROW, "2022/09/01 00:21,010,SECRET1,020,B1,abc,US Dollar,5.00,US Dollar,ACH,0");

        long batchRunId = loadIbmDataset.start(1L);
        loadIbmDataset.run(batchRunId);

        assertThat(batchRun(batchRunId)).containsEntry("status", "FAILED");
        assertThat((String) batchRun(batchRunId).get("error_message"))
                .contains("금액 형식")
                .doesNotContain("SECRET");
        assertThat(count("select count(*) from ops.transactions")).isZero();
    }

    @Test
    void 파일이_없으면_배치_행을_만들지_않고_거절한다() throws IOException {
        Files.delete(DIR.resolve(DatasetFiles.ACCOUNTS));

        assertThatThrownBy(() -> loadIbmDataset.start(1L))
                .isInstanceOf(DatasetFilesMissingException.class)
                .satisfies(e -> assertThat(((DatasetFilesMissingException) e).details())
                        .containsEntry("missing_files", java.util.List.of(DatasetFiles.ACCOUNTS)));
        assertThat(count("select count(*) from ops.batch_runs")).isZero();
    }

    @Test
    void 이미_적재된_DB는_다시_적재하지_않는다() {
        loadIbmDataset.run(loadIbmDataset.start(1L));

        assertThatThrownBy(() -> loadIbmDataset.start(1L)).isInstanceOf(BatchAlreadyDoneException.class);
        assertThat(count("select count(*) from ops.batch_runs")).isEqualTo(1);
    }

    @Test
    void 실행_중인_적재가_있으면_날짜와_상관없이_거절한다() {
        jdbc.update("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('LOAD_DATASET', '2026-01-01', 'RUNNING', 1)""");

        assertThatThrownBy(() -> loadIbmDataset.start(1L)).isInstanceOf(BatchRunningException.class);
    }

    @Test
    void 실패한_적재_뒤에는_다시_적재할_수_있다() throws IOException {
        writeFiles(LAUNDERING_ROW, "2022/09/01 00:21,010,A1,020,B1,abc,US Dollar,5.00,US Dollar,ACH,0");
        loadIbmDataset.run(loadIbmDataset.start(1L));

        writeFiles(LAUNDERING_ROW, SELF_ROW);
        long retry = loadIbmDataset.start(1L);
        loadIbmDataset.run(retry);

        assertThat(batchRun(retry)).containsEntry("status", "SUCCEEDED");
        assertThat(count("select min(source_row_number) from ops.transactions")).isEqualTo(1);
    }

    @Test
    void 성공_기록이_실패하면_적재도_롤백되고_실패로_남는다() {
        // 트랜잭션 프록시를 거치지 않도록 프록시 안쪽 스파이에 스텁을 건다.
        DatasetLoadRecorder spy = AopTestUtils.getUltimateTargetObject(recorder);
        doThrow(new IllegalStateException("기록 실패")).when(spy).succeed(anyLong(), anyLong(), anyLong());

        long batchRunId = loadIbmDataset.start(1L);
        loadIbmDataset.run(batchRunId);

        assertThat(batchRun(batchRunId)).containsEntry("status", "FAILED");
        assertThat(count("select count(*) from ops.transactions")).isZero();
        assertThat(count("select count(*) from ops.source_files")).isZero();

        // 데이터와 상태가 같이 롤백돼서 다시 적재할 수 있다.
        reset(spy);
        long retry = loadIbmDataset.start(1L);
        loadIbmDataset.run(retry);
        assertThat(batchRun(retry)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void 기동_시_남은_적재_RUNNING만_FAILED로_정리한다() {
        jdbc.update("""
                insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                values ('LOAD_DATASET', '2026-01-01', 'RUNNING', 1), ('RULE_SCAN', '2022-09-02', 'RUNNING', 1)""");

        recorder.failAbandonedLoads();

        assertThat(jdbc.queryForObject(
                "select status from ops.batch_runs where batch_type = 'LOAD_DATASET'", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select error_message from ops.batch_runs where batch_type = 'LOAD_DATASET'", String.class))
                .isEqualTo(DatasetLoadRecorder.ABANDONED_MESSAGE);
        assertThat(jdbc.queryForObject(
                "select status from ops.batch_runs where batch_type = 'RULE_SCAN'", String.class)).isEqualTo("RUNNING");
        long retry = loadIbmDataset.start(1L);
        assertThat(retry).isPositive();
    }

    private static void writeFiles(String... transactionRows) throws IOException {
        Files.writeString(DIR.resolve(DatasetFiles.TRANSACTIONS),
                TRANS_HEADER + "\r\n" + String.join("\r\n", transactionRows) + "\r\n");
        Files.writeString(DIR.resolve(DatasetFiles.ACCOUNTS), """
                Bank Name,Bank ID,Account Number,Entity ID,Entity Name
                Bank A,10,A1,E1,Corporation #1
                Bank B,20,B1,E2,Sole Proprietorship #2
                """);
        Files.writeString(DIR.resolve(DatasetFiles.PATTERNS),
                "BEGIN LAUNDERING ATTEMPT - CYCLE\n" + LAUNDERING_ROW + "\nEND LAUNDERING ATTEMPT\n");
    }

    private Map<String, Object> batchRun(long id) {
        return jdbc.queryForMap(
                "select batch_type, status, rows_in, rows_out, error_message from ops.batch_runs where id = ?", id);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("aml-dataset");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
