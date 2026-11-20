package com.team.aml.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-291 — 질문 답변 설정 `aml.ai.ask-enabled`(기본 false, 환경 변수 AML_AI_ASK_ENABLED)·
 * `aml.ai.ask-timeout-seconds`(기본 30). SCRUM-321 — `aml.ai.timeout-seconds`(기본 90, 1 이상)·`aml.ai.max-batch`(기본 500, 1~500).
 * application.yml 을 실제로 읽어 `AmlProperties` 로 묶어 본다.
 * 리포 루트 .env 와 셸 환경 변수에 흔들리지 않게 둘 다 빼고 테스트가 정한 값만 넣는다(AiMockSwitchTest 와 같은 방식).
 */
class AmlPropertiesDefaultsTest {

    @Configuration
    static class Empty {
    }

    @Test
    void 정상_아무것도_없으면_질문_답변은_꺼져_있고_예산은_30초_기존_90초는_그대로() {
        for (String profile : List.of("local", "test", "prod")) {
            AmlProperties.Ai ai = ai(profile, Map.of());
            assertThat(ai.askEnabled()).as(profile).isFalse();
            assertThat(ai.askTimeoutSeconds()).as(profile).isEqualTo(30);
            assertThat(ai.timeoutSeconds()).as(profile).isEqualTo(90);
            assertThat(ai.maxBatch()).as(profile).isEqualTo(500);
            assertThat(ai.askPerMinute()).as(profile).isEqualTo(5);   // SCRUM-302
        }
    }

    @Test
    void 정상_환경_변수_AML_AI_ASK_ENABLED_true면_켜진다() {
        assertThat(ai("local", Map.of("AML_AI_ASK_ENABLED", "true")).askEnabled()).isTrue();
    }

    @Test
    void 경계_yml에_칸이_빠져도_기본값이_0초가_아니라_30초_false다() {
        MockEnvironment env = new MockEnvironment().withProperty("aml.ai.base-url", "http://x");
        AmlProperties props = Binder.get(env).bind("aml", AmlProperties.class).get();
        assertThat(props.ai().askTimeoutSeconds()).isEqualTo(30);
        assertThat(props.ai().askEnabled()).isFalse();
        assertThat(props.ai().askPerMinute()).isEqualTo(5);
        assertThat(props.ai().timeoutSeconds()).isEqualTo(90);   // SCRUM-321 — 칸이 빠져도 0 이 아니라 90·500
        assertThat(props.ai().maxBatch()).isEqualTo(500);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}={1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "aml.ai.timeout-seconds, 1",
            "aml.ai.max-batch, 1",
            "aml.ai.max-batch, 500"})
    void 경계_timeout_seconds_1_max_batch_1과_500은_그대로_뜬다(String key, int value) {   // SCRUM-321
        MockEnvironment env = new MockEnvironment().withProperty(key, String.valueOf(value));
        AmlProperties.Ai ai = Binder.get(env).bind("aml", AmlProperties.class).get().ai();
        assertThat(key.endsWith("timeout-seconds") ? ai.timeoutSeconds() : ai.maxBatch()).isEqualTo(value);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}={1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "aml.ai.timeout-seconds, 0",
            "aml.ai.timeout-seconds, -1",
            "aml.ai.max-batch, 0",
            "aml.ai.max-batch, -1",
            "aml.ai.max-batch, 501"})
    void 실패_timeout_seconds_0_음수_max_batch_0_음수_501이면_칸_이름과_함께_묶기에_실패한다(String key, String value) {   // SCRUM-321
        MockEnvironment env = new MockEnvironment().withProperty(key, value);
        assertThatThrownBy(() -> Binder.get(env).bind("aml", AmlProperties.class))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining(key);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}=\"\"")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"aml.ai.timeout-seconds", "aml.ai.max-batch"})
    void 실패_timeout_seconds_max_batch가_빈_값이면_묶기에_실패한다(String key) {   // SCRUM-321
        MockEnvironment env = new MockEnvironment().withProperty(key, "");
        assertThatThrownBy(() -> Binder.get(env).bind("aml", AmlProperties.class))
                .isInstanceOf(BindException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}=0")
    @org.junit.jupiter.params.provider.CsvSource({
            "AML_AI_TIMEOUT_SECONDS, aml.ai.timeout-seconds",
            "AML_AI_MAX_BATCH, aml.ai.max-batch"})
    void 실패_application_yml에서_환경_변수로_0을_넣으면_기동하지_않는다(String envName, String key) {   // SCRUM-321
        assertThatThrownBy(() -> ai("local", Map.of(envName, "0")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining(key);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "ask-per-minute=\"{0}\"")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "-1"})
    void 실패_ask_per_minute가_0_이하면_묶기에_실패한다(String value) {   // 0 이면 아무도 못 묻는데 버튼은 보인다 (SCRUM-302)
        MockEnvironment env = new MockEnvironment().withProperty("aml.ai.ask-per-minute", value);
        assertThatThrownBy(() -> Binder.get(env).bind("aml", AmlProperties.class))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("ask-per-minute");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "ask-timeout-seconds=\"{0}\"")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "0", "-1"})
    void 실패_ask_timeout_seconds가_빈_값_0_음수면_묶기에_실패한다(String value) {
        MockEnvironment env = new MockEnvironment().withProperty("aml.ai.ask-timeout-seconds", value);
        assertThatThrownBy(() -> Binder.get(env).bind("aml", AmlProperties.class))
                .isInstanceOf(BindException.class);
    }

    @Test
    void 실패_application_yml에서_ask_timeout_seconds가_0이면_기동하지_않는다() {
        assertThatThrownBy(() -> ai("local", Map.of("AML_AI_ASK_TIMEOUT_SECONDS", "0")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("ask-timeout-seconds");
    }

    @Test
    void 실패_AML_AI_ASK_ENABLED가_참거짓으로_못_읽는_값이면_묶기에_실패한다() {   // yes·on·1 은 Spring 이 true 로 읽음
        assertThatThrownBy(() -> ai("local", Map.of("AML_AI_ASK_ENABLED", "maybe")))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("aml");
    }

    private static AmlProperties.Ai ai(String profile, Map<String, Object> osEnv) {
        SpringApplicationBuilder app = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .initializers(ctx -> {
                    MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
                    List<String> dotEnvSources = sources.stream()
                            .map(PropertySource::getName)
                            .filter(name -> name.contains("file [.env]"))
                            .toList();
                    dotEnvSources.forEach(sources::remove);
                    sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new SystemEnvironmentPropertySource(
                                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, osEnv));
                    sources.addLast(new MapPropertySource("empty", Map.of()));
                });
        try (ConfigurableApplicationContext ctx = app.run()) {
            return Binder.get(ctx.getEnvironment()).bind("aml", AmlProperties.class).get().ai();
        }
    }
}
