package com.team.aml.source.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.source.application.DatasetLoadRecorder;
import com.team.aml.source.application.LoadIbmDataset;
import com.team.aml.source.application.RequestIbmDatasetLoad;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 적재 API(202·비동기)와 배치 조회 API (SCRUM-70). 룰 스캔·점수도 202 (SCRUM-349). */
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

    @Autowired
    LoadIbmDataset loadIbmDataset;

    @Autowired
    DatasetLoadRecorder recorder;

    @Autowired
    BatchRunQuery batchRuns;

    @Autowired
    CurrentUser currentUser;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() throws IOException {
        jdbc.execute("truncate table ops.transactions_staging, ops.patterns_staging, ops.accounts_staging");
        jdbc.update("delete from ops.alert_scores");        // 룰 스캔·점수 테스트가 남긴 것 (SCRUM-349)
        jdbc.update("delete from ops.alert_transactions");
        jdbc.update("delete from ops.alerts");
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
        assertThat(done.get("summary").isNull()).isTrue();   // 결과 상세는 룰 스캔·점수만 (SCRUM-349)
    }

    @Test
    void 룰_스캔과_점수도_202로_받고_결과_상세는_배치_조회의_summary로() throws Exception {   // SCRUM-349
        waitUntilFinished(body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf())).andReturn(), 202)
                .get("batch_run_id").asLong());

        JsonNode scan = body(mvc.perform(post("/api/v1/admin/batches/rule-scan").param("date", "2022-09-01")
                .with(admin()).with(csrf())).andReturn(), 202);
        assertThat(scan.get("batch_type").asText()).isEqualTo("RULE_SCAN");
        assertThat(scan.get("status").asText()).isEqualTo("RUNNING");   // 실행기에 넘기기 전에 읽은 상태
        assertThat(scan.get("summary").isNull()).isTrue();
        JsonNode scanned = waitUntilFinished(scan.get("batch_run_id").asLong());
        assertThat(scanned.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode byRule = scanned.get("summary").get("alerts_by_rule");
        assertThat(byRule).hasSize(6);   // 0건 룰도 빠지지 않음
        assertThat(byRule.get(0).get("rule_id").asText()).isEqualTo("R01_FAN_OUT");
        assertThat(byRule.get(0).has("rule_version")).isTrue();
        assertThat(byRule.get(0).has("alert_count")).isTrue();

        JsonNode again = body(mvc.perform(post("/api/v1/admin/batches/rule-scan").param("date", "2022-09-01")
                .with(admin()).with(csrf())).andReturn(), 409);   // 거절은 지금처럼 요청 때 바로
        assertThat(again.get("error").get("code").asText()).isEqualTo("BATCH_ALREADY_DONE");

        JsonNode score = body(mvc.perform(post("/api/v1/admin/batches/score").param("date", "2022-09-01")
                .with(admin()).with(csrf())).andReturn(), 202);
        assertThat(score.get("batch_type").asText()).isEqualTo("SCORE_REQUEST");
        assertThat(score.get("status").asText()).isEqualTo("RUNNING");
        JsonNode scored = waitUntilFinished(score.get("batch_run_id").asLong());
        assertThat(scored.get("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode summary = scored.get("summary");
        for (String field : List.of("requested", "scored", "failed", "retryable", "chunks", "model_versions")) {
            assertThat(summary.has(field)).as(field).isTrue();
        }
        assertThat(summary.get("requested").asInt()).isEqualTo(scored.get("rows_in").asInt());

        JsonNode listed = body(mvc.perform(get("/api/v1/admin/batches").param("type", "RULE_SCAN").with(admin()))
                .andReturn(), 200);
        assertThat(listed.get("items").get(0).get("summary").get("alerts_by_rule")).hasSize(6);   // 목록에도
    }

    @Test
    void 룰_스캔_거절은_202_없이_409이고_배치_행을_남기지_않는다() throws Exception {   // SCRUM-349 — 적재 전
        JsonNode error = body(mvc.perform(post("/api/v1/admin/batches/rule-scan").param("date", "2022-09-01")
                .with(admin()).with(csrf())).andReturn(), 409);
        assertThat(error.get("error").get("code").asText()).isEqualTo("DATASET_NOT_LOADED");
        JsonNode scoreError = body(mvc.perform(post("/api/v1/admin/batches/score").param("date", "2022-09-01")
                .with(admin()).with(csrf())).andReturn(), 409);
        assertThat(scoreError.get("error").get("code").asText()).isEqualTo("RULE_SCAN_NOT_DONE");
        assertThat(jdbc.queryForObject("select count(*) from ops.batch_runs", Long.class)).isZero();
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
    void 실행기가_거절하면_FAILED로_남기고_예외를_다시_던진다_HTTP로는_500() throws Exception {   // #56 리뷰
        TaskExecutor rejecting = task -> { throw new TaskRejectedException("full"); };
        var sut = new RequestIbmDatasetLoad(loadIbmDataset, recorder, batchRuns, currentUser, rejecting);
        AppUserDetails admin = new AppUserDetails(1L, "system", "SYSTEM", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            assertThatThrownBy(sut::execute).isInstanceOf(TaskRejectedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }

        Map<String, Object> run = jdbc.queryForMap("select status, error_message from ops.batch_runs");
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_message")).isEqualTo("적재 작업을 시작하지 못함 (TaskRejectedException)");

        // RUNNING 이 남지 않아 다음 적재는 BATCH_RUNNING 에 막히지 않는다
        long next = body(mvc.perform(post("/api/v1/admin/batches/load").with(admin()).with(csrf())).andReturn(), 202)
                .get("batch_run_id").asLong();
        assertThat(waitUntilFinished(next).get("status").asText()).isEqualTo("SUCCEEDED");
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
        for (String[] param : new String[][]{{"type", "WRONG"}, {"size", "101"}, {"sort", "id,desc"}}) {
            JsonNode error = body(mvc.perform(get("/api/v1/admin/batches").param(param[0], param[1]).with(admin()))
                    .andReturn(), 400);
            assertThat(error.get("error").get("details").get("parameter").asText()).as(param[0] + "=" + param[1])
                    .isEqualTo(param[0]);
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
        throw new AssertionError("배치가 20초 안에 끝나지 않았습니다");
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
