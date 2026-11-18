package com.team.aml.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logback-spring.xml 로그 형식 (SCRUM-272, 03_shared §8 로그 형식 — AI 와 같은 칸 이름).
 * 스프링 컨텍스트 없이 LoggingSystem 으로 프로필만 바꿔 다시 읽는다. 다른 테스트가 같은 JVM 을 쓰니
 * 끝나면 test 프로필로 되돌리고 로거 레벨도 되돌린다(이미 뜬 컨텍스트는 로깅을 다시 초기화하지 않는다).
 */
@ExtendWith(OutputCaptureExtension.class)
class JsonLogFormatTest {

    private static final Logger log = LoggerFactory.getLogger(JsonLogFormatTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    private final LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
    private final Map<String, LogLevel> savedLevels = new LinkedHashMap<>();

    @BeforeEach
    void saveLevels() {
        for (LoggerConfiguration c : system.getLoggerConfigurations()) {
            if (c.getConfiguredLevel() != null) savedLevels.put(c.getName(), c.getConfiguredLevel());
        }
    }

    @AfterEach
    void restore() {
        MDC.clear();
        reload("test");
        savedLevels.forEach(system::setLogLevel);
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_json_프로필은_한_줄_JSON이고_필수_칸과_MDC_칸이_있다(CapturedOutput output) throws Exception {
        reload("test", "json");
        MDC.put("request_id", "rid-format-1");
        MDC.put("user_id", "7");
        MDC.put("target", "CASE-2022-000001");

        log.info("format_probe_ok item_count=3");

        JsonNode line = onlyLine(output, "format_probe_ok");
        assertThat(line.path("ts").asText()).matches(TS);
        assertThat(line.path("level").asText()).isEqualTo("INFO");
        assertThat(line.path("service").asText()).isEqualTo("backend");
        assertThat(line.path("logger").asText()).isEqualTo(JsonLogFormatTest.class.getName());
        assertThat(line.path("msg").asText()).isEqualTo("format_probe_ok item_count=3");
        assertThat(line.path("request_id").asText()).isEqualTo("rid-format-1");
        assertThat(line.path("user_id").asText()).isEqualTo("7");
        assertThat(line.path("target").asText()).isEqualTo("CASE-2022-000001");
    }

    @Test
    void 정상_기본_칸_이름은_남지_않는다(CapturedOutput output) throws Exception {
        reload("test", "json");

        log.warn("format_probe_names");

        JsonNode line = onlyLine(output, "format_probe_names");
        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.has("@timestamp")).isFalse();
        assertThat(line.has("@version")).isFalse();
        assertThat(line.has("message")).isFalse();
        assertThat(line.has("logger_name")).isFalse();
        assertThat(line.has("thread_name")).isFalse();
        assertThat(line.has("level_value")).isFalse();
    }

    @Test
    void 정상_prod도_json과_같은_칸이다(CapturedOutput output) throws Exception {
        reload("prod");
        MDC.put("request_id", "rid-format-prod");

        log.info("format_probe_prod");

        JsonNode line = onlyLine(output, "format_probe_prod");
        assertThat(line.path("ts").asText()).matches(TS);
        assertThat(line.path("service").asText()).isEqualTo("backend");
        assertThat(line.path("msg").asText()).isEqualTo("format_probe_prod");
        assertThat(line.path("request_id").asText()).isEqualTo("rid-format-prod");
        assertThat(line.has("@timestamp")).isFalse();
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_정해진_MDC_키만_나가고_case_no_같은_다른_키는_안_나간다(CapturedOutput output) throws Exception {
        reload("test", "json");
        MDC.put("request_id", "rid-format-2");
        MDC.put("case_no", "CASE-2022-000002");
        MDC.put("account_key", "10_8000EBD30");

        log.info("format_probe_mdc");

        JsonNode line = onlyLine(output, "format_probe_mdc");
        assertThat(line.has("case_no")).isFalse();
        assertThat(line.has("account_key")).isFalse();
        assertThat(line.toString()).doesNotContain("8000EBD30");
    }

    @Test
    void 경계_요청_밖_로그는_request_id_칸이_없고_나머지_필수_칸은_있다(CapturedOutput output) throws Exception {
        reload("test", "json");

        log.info("format_probe_no_request");

        JsonNode line = onlyLine(output, "format_probe_no_request");
        assertThat(line.has("request_id")).isFalse();
        assertThat(line.has("user_id")).isFalse();
        for (String key : List.of("ts", "level", "service", "logger", "msg")) {
            assertThat(line.has(key)).as(key).isTrue();
        }
    }

    @Test
    void 경계_메시지에_줄바꿈이_있어도_한_줄_JSON이다(CapturedOutput output) throws Exception {
        reload("test", "json");

        log.info("format_probe_newline first\nsecond");

        JsonNode line = onlyLine(output, "format_probe_newline");
        assertThat(line.path("msg").asText()).isEqualTo("format_probe_newline first\nsecond");
        assertThat(output.getAll().lines().filter(l -> l.startsWith("second"))).isEmpty();
    }

    @Test
    void 경계_예외_스택도_같은_한_줄_안에_있다(CapturedOutput output) throws Exception {
        reload("test", "json");

        log.error("format_probe_error", new IllegalStateException("probe-boom"));

        JsonNode line = onlyLine(output, "format_probe_error");
        assertThat(line.path("level").asText()).isEqualTo("ERROR");
        assertThat(line.toString()).contains("probe-boom");
        assertThat(output.getAll().lines().filter(l -> l.trim().startsWith("at com.team.aml"))).isEmpty();
    }

    // --- 실패(회귀) ----------------------------------------------------------

    @Test
    void 회귀_json이_없으면_test는_읽기_쉬운_텍스트_그대로다(CapturedOutput output) {
        reload("test");
        MDC.put("request_id", "rid-format-text");

        log.info("format_probe_text");

        List<String> lines = output.getAll().lines().filter(l -> l.contains("format_probe_text")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotStartWith("{").contains("INFO").contains("[rid-format-text]");
    }

    @Test
    void 회귀_local만_켜도_텍스트다(CapturedOutput output) {
        reload("local");

        log.info("format_probe_local");

        List<String> lines = output.getAll().lines().filter(l -> l.contains("format_probe_local")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotStartWith("{");
    }

    @Test
    void 회귀_local_json이면_JSON이고_텍스트_줄이_겹치지_않는다(CapturedOutput output) throws Exception {
        reload("local", "json");

        log.info("format_probe_local_json");

        JsonNode line = onlyLine(output, "format_probe_local_json");
        assertThat(line.path("service").asText()).isEqualTo("backend");
    }

    // --- 도우미 -------------------------------------------------------------

    private void reload(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    }

    /** 표식이 든 줄이 정확히 하나이고, 그 줄이 JSON 객체 하나다. */
    private static JsonNode onlyLine(CapturedOutput output, String marker) throws Exception {
        List<String> lines = output.getAll().lines().filter(l -> l.contains(marker)).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("{").endsWith("}");
        JsonNode node = JSON.readTree(lines.get(0));
        assertThat(node.isObject()).isTrue();
        return node;
    }
}
