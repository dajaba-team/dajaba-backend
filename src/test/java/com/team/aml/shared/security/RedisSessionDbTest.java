package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.AmlApplication;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 세션을 진짜 Redis 에 (SCRUM-273) — compose 와 같은 이미지·명령(비밀번호·noeviction·maxmemory), aml.session.redis.enabled=true.
 * 진짜 HTTP(쿠키 그대로)로 로그인·만료·끊기·로그아웃·재시작을 본다. 기본(메모리) 모드 테스트는 그대로 둔다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aml.session.redis.enabled=true",
        "aml.dev-users.enabled=true", "aml.dev-users.password=" + RedisSessionDbTest.PASSWORD})
@ActiveProfiles("test")
class RedisSessionDbTest {

    static final String PASSWORD = "test-redis-session-1234";
    static final String REDIS_PASSWORD = "redis-test-pass";
    private static final AtomicLong SEQUENCE = new AtomicLong();

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

    /** compose.yaml redis 와 같은 태그·명령. */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.3-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--appendonly", "yes",
                    "--maxmemory", "200mb", "--maxmemory-policy", "noeviction")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper om;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisConnectionFactory connections;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

    // --- 저장 위치·쿠키 --------------------------------------------------------

    @Test
    void 정상_로그인하면_세션_키가_db0_aml_session_아래에_생기고_쿠키는_그대로() throws Exception {
        Browser b = new Browser(port);
        HttpResponse<String> login = b.login("inv01", PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);

        String setCookie = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Secure");   // test 는 prod 가 아님

        String sessionId = b.sessionId();
        assertThat(redisTemplate.hasKey("aml:session:sessions:" + sessionId)).isTrue();
        assertThat(sessions.findByPrincipalName("inv01")).containsKey(sessionId);   // login_id 색인
        assertThat(redisTemplate.getExpire("aml:session:sessions:" + sessionId)).isBetween(30 * 60L, 35 * 60L);   // 30m + 5분
        assertThat(redis.execInContainer("redis-cli", "-a", REDIS_PASSWORD, "--no-auth-warning", "-n", "1", "dbsize")
                .getStdout().trim()).isEqualTo("0");   // db 1 은 Langfuse 몫 — 세션은 안 들어감
    }

    @Test
    void 정상_세션에는_비밀번호_해시가_없다() throws Exception {
        Browser b = new Browser(port);
        b.login("inv01", PASSWORD);
        String stored;
        try (RedisConnection c = connections.getConnection()) {
            Map<byte[], byte[]> hash = c.hashCommands().hGetAll(("aml:session:sessions:" + b.sessionId()).getBytes());
            stored = hash.values().stream().map(v -> new String(v, StandardCharsets.ISO_8859_1)).reduce("", String::concat);
        }
        assertThat(stored).contains("inv01");   // 세션 주체(AppUserDetails)는 들어감
        assertThat(stored).doesNotContain("$2a$").doesNotContain(PASSWORD);
    }

    @Test
    void 정상_로그인_안_한_401에는_세션이_생기지_않는다() throws Exception {
        Browser b = new Browser(port);
        HttpResponse<String> me = b.get("/api/v1/auth/me");
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
    }

    // --- 재시작 ----------------------------------------------------------------

    @Test
    void 정상_앱_컨텍스트를_새로_띄워도_같은_쿠키로_me가_200() throws Exception {
        Browser b = new Browser(port);
        b.login("apr01", PASSWORD);

        // 명령줄 인자로 — builder.properties() 는 기본값이라 application.yml 에 진다
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(AmlApplication.class)
                .profiles("test")
                .run("--server.port=0", "--management.server.port=0",
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=postgres", "--spring.datasource.password=postgres",
                        "--spring.flyway.enabled=false",
                        "--aml.session.redis.enabled=true",
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--spring.data.redis.password=" + REDIS_PASSWORD)) {
            int otherPort = Integer.parseInt(restarted.getEnvironment().getProperty("local.server.port"));
            HttpResponse<String> me = b.on(otherPort).get("/api/v1/auth/me");
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(om.readTree(me.body()).get("login_id").asText()).isEqualTo("apr01");
        }
    }

    // --- 세션 끊기 -------------------------------------------------------------

    @Test
    void 정상_같은_계정_두_번째_로그인이_첫_세션을_끊는다() throws Exception {
        User u = newInvestigator();
        Browser first = new Browser(port);
        first.login(u.getLoginId(), PASSWORD);
        Browser second = new Browser(port);
        second.login(u.getLoginId(), PASSWORD);

        HttpResponse<String> old = first.get("/api/v1/auth/me");
        assertThat(old.statusCode()).isEqualTo(401);
        assertThat(code(old)).isEqualTo("UNAUTHENTICATED");
        assertThat(second.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void 정상_역할_변경_뒤_그_사람_세션의_다음_요청은_401() throws Exception {
        assertCutAfterAdmin("change-role", "{\"role\":\"APPROVER\"}");
    }

    @Test
    void 정상_비활성화_뒤_그_사람_세션의_다음_요청은_401() throws Exception {
        assertCutAfterAdmin("deactivate", "{}");
    }

    @Test
    void 정상_비밀번호_초기화_뒤_그_사람_세션의_다음_요청은_401() throws Exception {
        assertCutAfterAdmin("reset-password", "{\"new_password\":\"another-pass-5678\"}");
    }

    private void assertCutAfterAdmin(String verb, String body) throws Exception {
        User u = newInvestigator();
        Browser target = new Browser(port);
        target.login(u.getLoginId(), PASSWORD);
        Browser bystander = new Browser(port);
        bystander.login("inv02", PASSWORD);
        Browser admin = new Browser(port);
        admin.login("admin", PASSWORD);

        HttpResponse<String> changed = admin.post("/api/v1/admin/users/" + u.getId() + "/" + verb, body);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);

        HttpResponse<String> next = target.get("/api/v1/auth/me");
        assertThat(next.statusCode()).isEqualTo(401);
        assertThat(code(next)).isEqualTo("UNAUTHENTICATED");
        assertThat(bystander.get("/api/v1/auth/me").statusCode()).as("다른 사람 세션은 그대로").isEqualTo(200);
        assertThat(admin.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
    }

    // --- 로그아웃 ---------------------------------------------------------------

    @Test
    void 정상_로그아웃하면_Redis에서_세션이_지워진다() throws Exception {
        User u = newInvestigator();
        Browser b = new Browser(port);
        b.login(u.getLoginId(), PASSWORD);
        String sessionId = b.sessionId();
        String key = "aml:session:sessions:" + sessionId;
        assertThat(redisTemplate.hasKey(key)).isTrue();

        assertThat(b.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);

        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(redisTemplate.hasKey("aml:session:sessions:expires:" + sessionId)).isFalse();
        assertThat(redisTemplate.opsForSet().size(
                "aml:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:"
                        + u.getLoginId())).as("login_id 색인에서도 빠짐").isZero();
        assertThat(b.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    // --- Redis 쓰기 거부 -------------------------------------------------------

    @Test
    void 실패_maxmemory로_쓰기가_거부되면_로그인과_로그인한_요청이_503_JSON() throws Exception {
        Browser before = new Browser(port);
        before.login("inv02", PASSWORD);
        try {
            redis.execInContainer("redis-cli", "-a", REDIS_PASSWORD, "--no-auth-warning", "config", "set", "maxmemory", "1");

            HttpResponse<String> login = new Browser(port).login("inv01", PASSWORD);
            assertThat(login.statusCode()).isEqualTo(503);
            assertThat(code(login)).isEqualTo("SESSION_STORE_UNAVAILABLE");
            assertThat(login.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");

            HttpResponse<String> me = before.get("/api/v1/auth/me");   // 요청 끝에 마지막 접근 시각을 쓰다 거부됨
            assertThat(me.statusCode()).isEqualTo(503);
            assertThat(code(me)).isEqualTo("SESSION_STORE_UNAVAILABLE");
        } finally {
            redis.execInContainer("redis-cli", "-a", REDIS_PASSWORD, "--no-auth-warning", "config", "set", "maxmemory", "200mb");
        }
        assertThat(before.get("/api/v1/auth/me").statusCode()).as("메모리가 풀리면 다시 그대로").isEqualTo(200);
    }

    // --- 도우미 -----------------------------------------------------------------

    private User newInvestigator() {
        String loginId = "rs" + System.nanoTime() % 1_000_000 + "_" + SEQUENCE.incrementAndGet();
        return users.save(User.create(loginId, "세션시험", Role.INVESTIGATOR, encoder.encode(PASSWORD)));
    }

    private String code(HttpResponse<String> res) throws Exception {
        JsonNode body = om.readTree(res.body());
        return body.path("error").path("code").asText();
    }

    /** 쿠키(SESSION·XSRF-TOKEN)를 들고 다니는 브라우저 하나. 쿠키는 포트와 상관없이 localhost 에 붙는다(재시작 시험). */
    static final class Browser {
        private final CookieManager cookies;
        private final HttpClient http;
        private final int port;

        Browser(int port) {
            this(port, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }

        private Browser(int port, CookieManager cookies) {
            this.port = port;
            this.cookies = cookies;
            this.http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).cookieHandler(cookies).build();
        }

        Browser on(int otherPort) {
            return new Browser(otherPort, cookies);
        }

        HttpResponse<String> login(String loginId, String password) throws Exception {
            return send(HttpRequest.newBuilder(uri(JsonLoginConfigurer.LOGIN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"login_id\":\"" + loginId + "\",\"password\":\"" + password + "\"}")));
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        /** 쓰기 요청 — XSRF-TOKEN 쿠키가 아직 없으면 GET 한 번으로 받는다. */
        HttpResponse<String> post(String path, String json) throws Exception {
            if (cookie("XSRF-TOKEN") == null) {
                get("/api/v1/auth/me");
            }
            return send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
        }

        /** Spring Session 쿠키 값은 세션 ID 의 Base64. */
        String sessionId() {
            return new String(Base64.getDecoder().decode(cookie("SESSION")), StandardCharsets.UTF_8);
        }

        private String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst().orElse(null);
        }

        private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
