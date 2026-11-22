package com.team.aml.cases.application;

import com.team.aml.detection.application.RequestScores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named = "DEMO_PIPELINE_FIXTURE", matches = "true")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55472/demo_fixture",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:55472/demo_fixture",
        "aml.ai.base-url=http://127.0.0.1:55473", "aml.ai.mock=false",
        "aml.batch.chain-enabled=true", "aml.case-policy.version=fixture-case-v1",
        "aml.case-policy.low-to-medium=0.15", "aml.case-policy.medium-to-high=0.70"})
class DemoCasePipelineFixtureTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestScores scores;

    @Test
    void scoresSmallFrozenSliceAndAutomaticallyOpensExactlyTheSelectedCases() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("demo_fixture");
        assertThat(jdbc.queryForObject("select count(*) from ops.alerts", Long.class)).isZero();
        var loader = new ResourceDatabasePopulator(new FileSystemResource(System.getenv("DEMO_FIXTURE_SQL")));
        loader.setSqlScriptEncoding("UTF-8");
        loader.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("select count(*) from ops.alerts", Long.class)).isEqualTo(31);
        var selection = Files.readAllLines(Path.of(System.getenv("AML_DEMO_CASE_SELECTION_FILE")));
        LocalDate day = LocalDate.parse(selection.get(0));
        var expected = selection.stream().skip(1).map(line -> Long.parseLong(line.split("\t")[1])).toList();
        long run = scores.start(day, 1);
        scores.run(run, day);
        assertThat(jdbc.queryForObject("select count(*) from ops.alert_scores", Long.class)).isEqualTo(31);
        assertThat(jdbc.queryForList("select distinct model_version from ops.alert_scores", String.class))
                .containsExactly("xgb-hi-medium-r04-event-c2-31-v1");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForList(
                "select status from ops.batch_runs where batch_type='OPEN_CASES' and batch_date = ?",
                String.class, day)).containsExactly("SUCCEEDED"));
        assertThat(jdbc.queryForList("select alert_id from ops.cases", Long.class))
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(jdbc.queryForObject("select count(*) from ops.case_history", Long.class)).isEqualTo(30);
    }
}
