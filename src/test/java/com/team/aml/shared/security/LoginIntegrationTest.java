package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 진짜 로그인 (SCRUM-19) — 실제 PostgreSQL + 필터 체인 전체.
 * 사용자는 test 프로필에서 DevUserSeeder 를 켜서 만든다(admin·inv01·apr01·inv02, 비밀번호 PASSWORD).
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + LoginIntegrationTest.PASSWORD,
        "logging.level.com.team.aml=DEBUG"})   // local 과 같은 로그 수준에서 입력 login_id 가 안 찍히는지 본다
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LoginIntegrationTest.ApprovalsProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginIntegrationTest {

    static final String PASSWORD = "test-login-pass-1234";

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

    /** GET /approvals 핸들러가 아직 없어서(채영 W4) URL 권한만 보는 테스트용 핸들러. src/test 에만 있다. */
    @TestConfiguration
    @RestController
    static class ApprovalsProbe {
        @GetMapping("/api/v1/approvals/_probe") Map<String, String> get() { return Map.of("ok", "get"); }
        @PostMapping("/api/v1/approvals/_probe") Map<String, String> post() { return Map.of("ok", "post"); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired Environment env;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @BeforeEach
    void inactiveUser() {
        if (users.existsByLoginId("gone01")) return;
        User u = User.create("gone01", "퇴사자", Role.INVESTIGATOR, encoder.encode(PASSWORD));
        u.deactivate();
        users.save(u);
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_inv01_로그인하면_내정보와_같은_모양이고_me가_200() throws Exception {
        MvcResult r = login("inv01", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("login_id").asText()).isEqualTo("inv01");
        assertThat(body.get("role").asText()).isEqualTo("INVESTIGATOR");
        assertThat(body.has("id")).isTrue();
        assertThat(body.has("display_name")).isTrue();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(PASSWORD);

        MockHttpSession session = session(r);
        MvcResult me = mvc.perform(get("/api/v1/auth/me").session(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(me).get("login_id").asText()).isEqualTo("inv01");

        // 세션에는 해시 없는 AppUserDetails 만 (LoginCandidate·비밀번호가 남지 않는다)
        SecurityContext ctx = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(ctx.getAuthentication().getPrincipal()).isInstanceOf(AppUserDetails.class);
        assertThat(ctx.getAuthentication().getCredentials()).isNull();
    }

    @Test
    void 정상_로그아웃하면_204_이후_me는_401() throws Exception {
        MvcResult r = login("inv01", PASSWORD);
        MockHttpSession session = session(r);
        Cookie xsrf = xsrf(r);

        MvcResult out = mvc.perform(post("/api/v1/auth/logout").session(session)
                .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(204);

        MvcResult me = mvc.perform(get("/api/v1/auth/me").session(session)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(me)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 정상_로그인_성공응답에_새_XSRF_토큰_쿠키() throws Exception {
        Cookie old = new Cookie("XSRF-TOKEN", "before-login-token");
        MvcResult r = mvc.perform(post("/api/v1/auth/login").cookie(old)
                .contentType(MediaType.APPLICATION_JSON).content(body("inv01", PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Cookie fresh = xsrf(r);
        assertThat(fresh.getValue()).isNotBlank().isNotEqualTo(old.getValue());
    }

    @Test
    void 정상_approvals_권한은_메서드별() throws Exception {
        MvcResult admin = login("admin", PASSWORD);
        assertThat(probeGet(admin).getResponse().getStatus()).isEqualTo(200);
        MvcResult adminPost = probePost(admin);
        assertThat(adminPost.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(adminPost)).isEqualTo("FORBIDDEN");

        MvcResult approver = login("apr01", PASSWORD);
        assertThat(probePost(approver).getResponse().getStatus()).isEqualTo(200);

        MvcResult inv = probeGet(login("inv01", PASSWORD));
        assertThat(inv.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(inv)).isEqualTo("FORBIDDEN");
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_로그인하면_세션ID가_바뀐다() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String beforeId = before.getId();
        MvcResult r = mvc.perform(post("/api/v1/auth/login").session(before)
                .contentType(MediaType.APPLICATION_JSON).content(body("inv01", PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getRequest().getSession().getId()).isNotEqualTo(beforeId);
    }

    @Test
    void 경계_같은_계정_두번_로그인하면_첫_세션은_401() throws Exception {
        MockHttpSession first = session(login("inv02", PASSWORD));
        MockHttpSession second = session(login("inv02", PASSWORD));

        MvcResult old = mvc.perform(get("/api/v1/auth/me").session(first)).andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(401);
        assertThat(old.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(code(old)).isEqualTo("UNAUTHENTICATED");

        assertThat(mvc.perform(get("/api/v1/auth/me").session(second)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 경계_역할이_바뀐_뒤_다시_로그인해도_첫_세션은_끊긴다() throws Exception {
        User u = users.findByLoginId("role01").orElseGet(() ->
                users.save(User.create("role01", "역할 변경", Role.APPROVER, encoder.encode(PASSWORD))));
        MockHttpSession first = session(login("role01", PASSWORD));      // APPROVER 로 로그인
        u = users.findByLoginId("role01").orElseThrow();
        u.changeRole(Role.INVESTIGATOR);
        users.save(u);
        session(login("role01", PASSWORD));                              // INVESTIGATOR 로 다시 로그인

        MvcResult old = mvc.perform(get("/api/v1/approvals/_probe").session(first)).andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(401);        // 이전 역할(APPROVER)로 남지 않는다
        assertThat(code(old)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 경계_끊긴_세션의_쓰기요청도_토큰이_맞으면_401() throws Exception {
        MvcResult firstLogin = login("apr01", PASSWORD);
        login("apr01", PASSWORD);
        Cookie xsrf = xsrf(firstLogin);
        MvcResult r = mvc.perform(post("/api/v1/approvals/_probe").session(session(firstLogin))
                .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);           // 토큰이 없으면 CSRF 가 먼저라 403 CSRF_INVALID
        assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void 경계_세션_만료는_30분() {
        assertThat(env.getProperty("server.servlet.session.timeout", Duration.class)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 경계_목업_로그인_경로가_없다() {
        List<String> patterns = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).toList();
        assertThat(patterns).contains("/api/v1/auth/me").noneMatch(p -> p.contains("mock-login"));
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_틀린비밀번호_없는ID_비활성_SYSTEM은_모두_같은_401() throws Exception {
        List<MvcResult> results = List.of(
                login("inv01", "wrong-password"),
                login("nobody-here", PASSWORD),
                login("gone01", PASSWORD),
                login("system", PASSWORD));
        String first = results.get(0).getResponse().getContentAsString();
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(code(r)).isEqualTo("LOGIN_FAILED");
            assertThat(Arrays.equals(r.getResponse().getContentAsByteArray(), results.get(0).getResponse().getContentAsByteArray()))
                    .as("응답 본문이 사유마다 같아야 한다: %s vs %s", r.getResponse().getContentAsString(), first).isTrue();
            assertThat(r.getRequest().getSession(false) == null
                    || r.getRequest().getSession(false).getAttribute("SPRING_SECURITY_CONTEXT") == null).isTrue();
        }
    }

    @Test
    void 실패_본문이_비었거나_필드가_없으면_400() throws Exception {
        for (String content : List.of("", "{}", "{\"login_id\":\"inv01\"}", "{\"password\":\"x\"}", "not-json")) {
            MvcResult r = mvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
            assertThat(r.getResponse().getStatus()).as(content).isEqualTo(400);
            assertThat(code(r)).as(content).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void 실패_JSON이_아니거나_너무_크면_400() throws Exception {
        MvcResult plain = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.TEXT_PLAIN).content(body("inv01", PASSWORD))).andReturn();   // 로그인 CSRF 경로
        assertThat(plain.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(plain)).isEqualTo("INVALID_REQUEST");

        MvcResult longId = login("x".repeat(65), PASSWORD);
        assertThat(longId.getResponse().getStatus()).isEqualTo(400);

        MvcResult huge = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"inv01\",\"password\":\"" + PASSWORD + "\",\"pad\":\"" + "x".repeat(5000) + "\"}")).andReturn();
        assertThat(huge.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void 실패_입력한_login_id는_로그에_남지_않는다(CapturedOutput output) throws Exception {
        login("leaky-login-id-zz9", "wrong-password-zz9");
        assertThat(output.getAll()).doesNotContain("leaky-login-id-zz9").doesNotContain("wrong-password-zz9");
    }

    // --- 도우미 -------------------------------------------------------------

    private MvcResult login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body(loginId, password))).andReturn();
    }

    private MvcResult probeGet(MvcResult login) throws Exception {
        return mvc.perform(get("/api/v1/approvals/_probe").session(session(login))).andReturn();
    }

    private MvcResult probePost(MvcResult login) throws Exception {
        Cookie xsrf = xsrf(login);
        return mvc.perform(post("/api/v1/approvals/_probe").session(session(login))
                .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())).andReturn();
    }

    private String body(String loginId, String password) throws Exception {
        return om.writeValueAsString(Map.of("login_id", loginId, "password", password));
    }

    private static MockHttpSession session(MvcResult r) {
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    /** 브라우저에 최종으로 남는 XSRF-TOKEN — 응답 순서대로 마지막 쿠키. 삭제(값 없음·Max-Age 0)로 끝나면 실패. */
    private static Cookie xsrf(MvcResult r) {
        Cookie last = null;
        for (Cookie c : r.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(c.getName())) last = c;
        }
        assertThat(last).as("XSRF-TOKEN 쿠키").isNotNull();
        assertThat(last.getValue()).as("마지막 XSRF-TOKEN 이 삭제 쿠키").isNotEmpty();
        assertThat(last.getMaxAge()).isNotZero();
        return new Cookie("XSRF-TOKEN", last.getValue());
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }
}
