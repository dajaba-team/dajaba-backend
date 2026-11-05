package com.team.aml.source.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.source.domain.DatasetExpectation;
import com.team.aml.source.domain.DatasetFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 적재 API(202·비동기)와 배치 조회 API (SCRUM-70). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasetLoadApiTest {

    private static final Path DIR = tempDir();
    private static final String SYSTEM_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";

    private static final String LAUNDERING_ROW = "2022/09/01 00:20,010,A1,020,B1,100.00,US Dollar,100.00,US Dollar,ACH,1";

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

    @TestConfiguration
    static class SmallDataset {
        @Bean
        @Primary
        DatasetExpectation smallExpectation() {
            return new DatasetExpectation(2, 1, 1, 1, 1,
                    LocalDateTime.of(2022, 9, 1, 0, 20), LocalDateTime.of(2022, 9, 1, 0, 21), 2, 2, 2);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() throws IOException {
        jdbc.execute("truncate table ops.transactions_staging, ops.patterns_staging, ops.accounts_staging");
        jdbc.update("delete from ops.transactions");
        jdbc.update("delete from ops.source_files");
        jdbc.update("delete from ops.accounts");
        jdbc.update("delete from ops.entities");
        jdbc.update("delete from ops.batch_runs");
        writeFiles();
    }

    @Test
    void 적재를_202로_받고_뒤에서_끝낸다() throws Exception {
        JsonNode accepted = body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf()))
                .andReturn(), 202);
        long id = accepted.get("batch_run_id").asLong();
        assertThat(accepted.get("batch_type").asText()).isEqualTo("LOAD_DATASET");
        assertThat(accepted.get("status").asText()).isEqualTo("RUNNING");   // 작업을 넘기기 전에 읽은 상태
        assertThat(accepted.get("started_at").asText()).matches(SYSTEM_TIME);

        JsonNode done = waitUntilFinished(id);
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("rows_in").asInt()).isEqualTo(2);
        assertThat(done.get("rows_out").asInt()).isEqualTo(2);
        assertThat(done.get("error_message").isNull()).isTrue();
        assertThat(done.get("finished_at").asText()).matches(SYSTEM_TIME);
        assertThat(done.get("triggered_by").get("login_id").asText()).isEqualTo("system");
    }

    @Test
    void 이미_적재된_DB면_409_BATCH_ALREADY_DONE() throws Exception {
        long id = body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf())).andReturn(), 202)
                .get("batch_run_id").asLong();
        waitUntilFinished(id);

        JsonNode error = body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf()))
                .andReturn(), 409);
        assertThat(error.get("error").get("code").asText()).isEqualTo("BATCH_ALREADY_DONE");
    }

    @Test
    void 파일이_없으면_409_DATASET_FILES_MISSING이고_배치_행을_만들지_않는다() throws Exception {
        Files.delete(DIR.resolve(DatasetFiles.PATTERNS));

        JsonNode error = body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf()))
                .andReturn(), 409);
        assertThat(error.get("error").get("code").asText()).isEqualTo("DATASET_FILES_MISSING");
        assertThat(error.get("error").get("details").get("missing_files").get(0).asText())
                .isEqualTo(DatasetFiles.PATTERNS);
        assertThat(jdbc.queryForObject("select count(*) from ops.batch_runs", Long.class)).isZero();
    }

    @Test
    void 적재는_ADMIN만_CSRF_토큰과_함께_부를_수_있다() throws Exception {
        assertThat(mvc.perform(post("/api/v1/admin/batches/load").with(csrf())).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/admin/batches/load").with(investigator()).with(csrf())).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/admin/batches/load").with(admin())).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/admin/batches").with(investigator())).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from ops.batch_runs", Long.class)).isZero();
    }

    @Test
    void 없는_배치는_404_BATCH_RUN_NOT_FOUND() throws Exception {
        JsonNode error = body(mvc.perform(get("/api/v1/admin/batches/999999").with(admin())).andReturn(), 404);
        assertThat(error.get("error").get("code").asText()).isEqualTo("BATCH_RUN_NOT_FOUND");
    }

    @Test
    void 목록은_필터_정렬_페이지를_적용한다() throws Exception {
        jdbc.update("""
                insert into ops.batch_runs(batch_type, batch_date, status, started_at, triggered_by) values
                ('RULE_SCAN', '2022-09-01', 'SUCCEEDED', '2026-11-03 01:00:00+00', 1),
                ('RULE_SCAN', '2022-09-02', 'FAILED',    '2026-11-03 02:00:00+00', 1),
                ('RULE_SCAN', '2022-09-03', 'SUCCEEDED', '2026-11-03 03:00:00+00', 1),
                ('SCORE_REQUEST', '2022-09-01', 'SUCCEEDED', '2026-11-03 04:00:00+00', 1)""");

        JsonNode page = body(mvc.perform(get("/api/v1/admin/batches")
                .param("type", "RULE_SCAN").param("status", "SUCCEEDED").with(admin())).andReturn(), 200);
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("batch_date").asText()).isEqualTo("2022-09-03");

        JsonNode sorted = body(mvc.perform(get("/api/v1/admin/batches")
                .param("sort", "batch_date,asc").param("size", "1").param("page", "1").with(admin())).andReturn(), 200);
        assertThat(sorted.get("total").asLong()).isEqualTo(4);
        assertThat(sorted.get("size").asInt()).isEqualTo(1);
        assertThat(sorted.get("items")).hasSize(1);
        assertThat(sorted.get("items").get(0).get("batch_date").asText()).isEqualTo("2022-09-01");

        JsonNode byDate = body(mvc.perform(get("/api/v1/admin/batches")
                .param("date", "2022-09-02").with(admin())).andReturn(), 200);
        assertThat(byDate.get("items").get(0).get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void 잘못된_목록_파라미터는_400_INVALID_REQUEST() throws Exception {
        for (String[] param : new String[][]{
                {"type", "WRONG"}, {"status", "DONE"}, {"date", "2022/09/01"},
                {"size", "101"}, {"size", "0"}, {"page", "-1"}, {"sort", "id,desc"}, {"sort", "started_at,up"}}) {
            JsonNode error = body(mvc.perform(get("/api/v1/admin/batches").param(param[0], param[1]).with(admin()))
                    .andReturn(), 400);
            assertThat(error.get("error").get("code").asText()).as(param[0] + "=" + param[1])
                    .isEqualTo("INVALID_REQUEST");
        }
    }

    private JsonNode waitUntilFinished(long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode run = body(mvc.perform(get("/api/v1/admin/batches/" + id).with(admin())).andReturn(), 200);
            if (!"RUNNING".equals(run.get("status").asText())) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("적재가 20초 안에 끝나지 않았습니다");
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static RequestPostProcessor admin() {
        return user(new AppUserDetails(1L, "system", "SYSTEM", "ADMIN"));
    }

    private static RequestPostProcessor investigator() {
        return user(new AppUserDetails(1L, "system", "SYSTEM", "INVESTIGATOR"));
    }

    private static void writeFiles() throws IOException {
        Files.writeString(DIR.resolve(DatasetFiles.TRANSACTIONS), """
                Timestamp,From Bank,Account,To Bank,Account,Amount Received,Receiving Currency,Amount Paid,Payment Currency,Payment Format,Is Laundering
                %s
                2022/09/01 00:21,010,A1,010,A1,5.00,US Dollar,5.00,US Dollar,ACH,0
                """.formatted(LAUNDERING_ROW));
        Files.writeString(DIR.resolve(DatasetFiles.ACCOUNTS), """
                Bank Name,Bank ID,Account Number,Entity ID,Entity Name
                Bank A,10,A1,E1,Corporation #1
                Bank B,20,B1,E2,Sole Proprietorship #2
                """);
        Files.writeString(DIR.resolve(DatasetFiles.PATTERNS),
                "BEGIN LAUNDERING ATTEMPT - CYCLE\n" + LAUNDERING_ROW + "\nEND LAUNDERING ATTEMPT\n");
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("aml-dataset-api");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
