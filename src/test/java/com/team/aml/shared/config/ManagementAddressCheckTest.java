package com.team.aml.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리 포트 듣는 주소 시작 검사 (03_shared §13, SCRUM-271 후속).
 * 환경 변수 MANAGEMENT_SERVER_ADDRESS 는 yml 의 ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1} 을 거치지 않고
 * management.server.address 를 바로 덮는다 — 빈 값이면 주소가 null 이 돼 Tomcat 이 모든 주소에 바인딩한다.
 * 그래서 실제 application.yml 을 읽고, 환경 변수 자리에 값을 넣어 앱이 뜨는지 본다.
 */
class ManagementAddressCheckTest {

    /**
     * 환경 변수 흉내 property source 이름. Boot Binder 는 이름이 "systemEnvironment" 이거나 "-systemEnvironment" 로 끝나야
     * SPRING_MAIN_LAZY_INITIALIZATION → spring.main.lazy-initialization 처럼 OS 환경 변수 규칙으로 읽는다.
     */
    private static final String TEST_ENV_SOURCE = "test-systemEnvironment";

    /** 실제 application.yml(관리 포트 8081 · 기본 주소 127.0.0.1)을 읽는 컨텍스트. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ManagementAddressCheck.class);

    /** OS 환경 변수 하나를 흉내 — 실제 환경 변수와 같은 SystemEnvironmentPropertySource, 맨 앞(가장 먼저 쓰임). */
    private ApplicationContextRunner withEnv(String name, String value) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(TEST_ENV_SOURCE, Map.of(name, value))));
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_변수가_없으면_127_0_0_1로_뜬다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getEnvironment().getProperty("management.server.address")).isEqualTo("127.0.0.1");
        });
    }

    @Test
    void 정상_compose처럼_0_0_0_0이면_뜬다() {
        withEnv("MANAGEMENT_SERVER_ADDRESS", "0.0.0.0").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_관리_포트가_앱_포트와_같으면_주소는_안_쓰여서_검사하지_않는다() {
        withEnv("MANAGEMENT_SERVER_ADDRESS", "")
                .withPropertyValues("server.port=8081")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_빈_문자열이면_앱이_뜨지_않는다() {
        withEnv("MANAGEMENT_SERVER_ADDRESS", "").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("MANAGEMENT_SERVER_ADDRESS")
                    .hasMessageContaining("127.0.0.1")
                    .hasMessageContaining("0.0.0.0");
        });
    }

    /**
     * 진짜 SpringApplication 으로 띄운다 — spring.main.lazy-initialization 은 SpringApplication 이 읽는 설정이라
     * ApplicationContextRunner 로는 재현이 안 된다. 환경 변수 자리(SystemEnvironmentPropertySource)에 넣고 application.yml 도 읽는다.
     */
    private static ConfigurableApplicationContext startWithEnv(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(TEST_ENV_SOURCE, env));
        SpringApplication app = new SpringApplication(ManagementAddressCheck.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(environment);
        return app.run();
    }

    @Test
    void 경계_전역_지연_초기화여도_주소가_있으면_뜬다() {
        try (ConfigurableApplicationContext ctx = startWithEnv(Map.of("SPRING_MAIN_LAZY_INITIALIZATION", "true"))) {
            assertThat(ctx.isActive()).isTrue();
        }
    }

    @Test
    void 실패_전역_지연_초기화여도_빈_주소면_앱이_뜨지_않는다() {
        assertThatThrownBy(() -> startWithEnv(Map.of(
                "SPRING_MAIN_LAZY_INITIALIZATION", "true",
                "MANAGEMENT_SERVER_ADDRESS", "")).close())
                .rootCause()
                .hasMessageContaining("MANAGEMENT_SERVER_ADDRESS");
    }

    @Test
    void 실패_공백이면_앱이_뜨지_않는다() {
        withEnv("MANAGEMENT_SERVER_ADDRESS", "   ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("MANAGEMENT_SERVER_ADDRESS");
        });
    }
}
