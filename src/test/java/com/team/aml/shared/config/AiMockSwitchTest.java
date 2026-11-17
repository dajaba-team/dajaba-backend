package com.team.aml.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AML_AI_MOCK 하나로 목업/진짜 AI 전환 (SCRUM-262) — application.yml 프로필이 aml.ai.mock 을 어떻게 묶는지.
 * 빈 설정 클래스로 Spring 을 띄워 설정만 읽는다(AI 서버·DB 없음).
 * 리포 루트 .env 와 실제 셸 환경 변수에 결과가 흔들리지 않게, 둘 다 빼고 테스트가 정한 값만 넣는다.
 * (spring.config.import 는 application.yml 안에 있어 설정값으로 비울 수 없어서 출처를 뺀다.)
 */
class AiMockSwitchTest {

    @Configuration
    static class Empty {
    }

    private static final Map<String, Object> NONE = Map.of();

    @Test
    void 정상_local에서_env파일식_AML_AI_MOCK_false면_진짜_AI다() {
        assertThat(mock("local", Map.of("AML_AI_MOCK", "false"), NONE)).isEqualTo("false");
    }

    @Test
    void 정상_local에서_아무것도_없으면_목업이다() {
        assertThat(mock("local", NONE, NONE)).isEqualTo("true");
    }

    @Test
    void 회귀_local에서_환경_변수_AML_AI_MOCK_false면_진짜_AI다() {
        assertThat(mock("local", NONE, Map.of("AML_AI_MOCK", "false"))).isEqualTo("false");
    }

    @Test
    void 경계_test_프로필은_env파일식_AML_AI_MOCK_false여도_목업_고정이다() {
        assertThat(mock("test", Map.of("AML_AI_MOCK", "false"), NONE)).isEqualTo("true");
    }

    /**
     * @param dotEnv .env 에서 읽은 것처럼 이름 그대로 들어가는 속성
     * @param osEnv  컨테이너·셸 환경 변수처럼 들어가는 값(느슨한 이름 연결)
     */
    private static String mock(String profile, Map<String, Object> dotEnv, Map<String, Object> osEnv) {
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
                    sources.addLast(new MapPropertySource("testDotEnv", dotEnv));
                });
        try (ConfigurableApplicationContext ctx = app.run()) {
            return ctx.getEnvironment().getProperty("aml.ai.mock");
        }
    }
}
