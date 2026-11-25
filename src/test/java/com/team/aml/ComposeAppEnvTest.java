package com.team.aml;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 11/25 전체 점검 QA-BE-02 — compose app 컨테이너에 초안 비동기 스위치(AML_STR_DRAFT_ASYNC)가 전달되지 않아
 * .env 에 켜 둬도 컨테이너는 늘 동기였다. app environment 에 한 줄 넘기고, 값이 없을 때의 기본은
 * application.yml 기본(aml.str.draft.async = false)과 같아 지금 동작 그대로인지 본다. Docker 없이 파일만 읽는다.
 */
class ComposeAppEnvTest {

    static final String KEY = "AML_STR_DRAFT_ASYNC";

    /** compose 보간 {@code ${NAME:-기본}} — 기본값을 꺼낸다. */
    private static final Pattern COMPOSE_DEFAULT = Pattern.compile("^\\$\\{" + KEY + ":-([^}]*)}$");
    /** Spring 자리표시자 {@code ${NAME:기본}} — application.yml 쪽 기본값. */
    private static final Pattern SPRING_DEFAULT = Pattern.compile("\\$\\{" + KEY + ":([^}]*)}");

    // --- 정상 ---

    @Test
    void 정상_app_environment에_초안_비동기_스위치가_환경_변수로_넘어간다() throws Exception {
        Object value = appEnvironment(Files.readString(Path.of("compose.yaml"))).get(KEY);

        assertThat(value).isInstanceOf(String.class);
        assertThat(COMPOSE_DEFAULT.matcher((String) value).matches())
                .as("app.environment.%s = ${%s:-기본} 모양", KEY, KEY).isTrue();
    }

    // --- 경계: 값이 없을 때 = 지금 동작(동기) ---

    @Test
    void 경계_값이_없을_때_compose_기본은_false이고_application_yml_기본과_같다() throws Exception {
        String composeValue = (String) appEnvironment(Files.readString(Path.of("compose.yaml"))).get(KEY);
        Matcher compose = COMPOSE_DEFAULT.matcher(composeValue);
        assertThat(compose.matches()).isTrue();

        Matcher spring = SPRING_DEFAULT.matcher(Files.readString(Path.of("src/main/resources/application.yml")));
        assertThat(spring.find()).as("application.yml 에 ${%s:기본}", KEY).isTrue();

        assertThat(compose.group(1)).isEqualTo("false");
        assertThat(compose.group(1)).isEqualTo(spring.group(1));
    }

    // --- 실패: 키가 빠진 compose 는 잡는다 ---

    @Test
    void 실패_app_environment에_키가_없으면_잡는다() {
        String withoutKey = """
                services:
                  app:
                    environment:
                      AML_AI_MOCK: ${AML_AI_MOCK:-true}
                """;

        assertThat(appEnvironment(withoutKey)).doesNotContainKey(KEY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appEnvironment(String composeText) {
        Map<String, Object> compose = new Yaml().load(composeText);
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> app = (Map<String, Object>) services.get("app");
        Object env = app.get("environment");
        assertThat(env).as("app.environment 는 맵 모양").isInstanceOf(Map.class);
        return (Map<String, Object>) env;
    }
}
