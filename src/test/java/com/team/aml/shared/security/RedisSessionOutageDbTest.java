package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 세션을 켰는데 Redis 가 없을 때 (SCRUM-273) — 앱은 뜨고, 세션이 필요한 요청만 503 SESSION_STORE_UNAVAILABLE JSON.
 * 로그에는 오류 클래스 이름만 — Redis 주소·비밀번호·세션 ID 는 없다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"aml.session.redis.enabled=true",
        "aml.dev-users.enabled=true", "aml.dev-users.password=" + RedisSessionOutageDbTest.PASSWORD})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RedisSessionOutageDbTest {

    static final String PASSWORD = "outage-login-pass-1234";
    static final String REDIS_PASSWORD = "outage-redis-pass";
    static final String SESSION_ID = "0b7f2c1e-0000-4000-8000-00000000abcd";
    static final int DEAD_PORT = freePort();

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

    /** 아무도 듣지 않는 포트 — 연결 거부. */
    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", () -> DEAD_PORT);
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    void 정상_Redis가_없어도_앱은_뜨고_health는_UP() throws Exception {
        HttpResponse<String> health = send(HttpRequest.newBuilder(uri("/actuator/health")).GET());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
    }

    @Test
    void 정상_쿠키_없는_요청은_Redis를_안_거쳐_401() throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).GET());
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(code(me)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 실패_맞는_비밀번호_로그인은_세션을_못_만들어_503_JSON이고_로그에는_클래스_이름만(CapturedOutput output) throws Exception {
        HttpResponse<String> login = login(PASSWORD);

        assertThat(login.statusCode()).isEqualTo(503);
        assertThat(code(login)).isEqualTo("SESSION_STORE_UNAVAILABLE");
        assertThat(login.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(login.headers().firstValue(RequestIdFilter.HEADER)).isPresent();
        assertThat(login.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
        assertThat(output).contains("session store unavailable error=RedisConnectionFailureException");
        assertNoSecretsIn(output);
    }

    @Test
    void 경계_틀린_비밀번호는_세션을_안_만들어_지금처럼_401_LOGIN_FAILED() throws Exception {
        HttpResponse<String> login = login("wrong-password-1234");
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(code(login)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void 실패_세션_쿠키가_있는_요청은_503_JSON이고_세션_ID는_로그에_없다(CapturedOutput output) throws Exception {
        String cookie = Base64.getEncoder().encodeToString(SESSION_ID.getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).header("Cookie", "SESSION=" + cookie).GET());

        assertThat(me.statusCode()).isEqualTo(503);
        assertThat(code(me)).isEqualTo("SESSION_STORE_UNAVAILABLE");
        assertThat(output).contains("session store unavailable error=RedisConnectionFailureException");
        assertNoSecretsIn(output);
        assertThat(output).doesNotContain(SESSION_ID).doesNotContain(cookie);
    }

    private static void assertNoSecretsIn(CapturedOutput output) {
        assertThat(output).doesNotContain(":" + DEAD_PORT).doesNotContain(REDIS_PASSWORD).doesNotContain("Unable to connect");
        // 원인 메시지·스택 없음 — 같은 JVM 의 다른 테스트 컨텍스트(예: RabbitMQ 재연결) 줄은 빼고 Redis·세션 줄만 본다
        assertThat(output.getAll().lines()
                .filter(l -> l.contains("redis") || l.contains("Redis") || l.contains("lettuce") || l.contains("session")))
                .noneMatch(l -> l.contains("Exception:") || l.contains("\tat "));
    }

    private HttpResponse<String> login(String password) throws Exception {
        return send(HttpRequest.newBuilder(uri(JsonLoginConfigurer.LOGIN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"login_id\":\"inv01\",\"password\":\"" + password + "\"}")));
    }

    private String code(HttpResponse<String> res) throws Exception {
        return om.readTree(res.body()).path("error").path("code").asText();
    }

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
