package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.str.domain.NotApproverException;
import com.team.aml.user.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 403 거부 경고 로그 (미결 30, SCRUM-74) — 실제 PostgreSQL + 필터 체인 전체.
 * 403 은 access_logs 에 남기지 않고 `access denied code=… method=… uri=…` 한 줄만 남긴다.
 * access_logs 는 다른 요청의 로그인 행이 있으니 절대값이 아니라 요청 전후 차이로 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AccessDeniedLogTest.PASSWORD,
        "logging.level.com.team.aml=DEBUG"})   // local 과 같은 로그 수준에서 값이 안 새는지 본다
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AccessDeniedLogTest.DeniedProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class AccessDeniedLogTest {

    static final String PASSWORD = "test-denied-pass-1234";
    static final String MVC_DENIED_MESSAGE = "mvc-denied-message-zz9";

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

    /** 컨트롤러 안에서 나는 403·409 를 만드는 테스트용 핸들러. src/test 에만 있다. */
    @TestConfiguration
    @RestController
    static class DeniedProbe {
        @GetMapping("/api/v1/_denied/domain") Map<String, String> domain() { throw new NotApproverException(Role.INVESTIGATOR); }
        @GetMapping("/api/v1/_denied/mvc") Map<String, String> mvc() { throw new AccessDeniedException(MVC_DENIED_MESSAGE); }
        @GetMapping("/api/v1/_denied/conflict") Map<String, String> conflict() { throw new ProbeConflictException(); }
    }

    static class ProbeConflictException extends DomainException {
        ProbeConflictException() { super("probe-conflict-message"); }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    // --- 경고 한 줄 (develop 에서는 실패) ---------------------------------------

    @Test
    void 정상_URL_역할_403은_경고_한_줄이고_user_id가_붙고_access_logs는_그대로(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);
        long before = accessLogs();

        MvcResult r = mvc.perform(get("/api/v1/admin/batches").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("FORBIDDEN");
        assertDeniedLine(output, "FORBIDDEN", "GET", "/api/v1/admin/batches", userId(login));
        assertThat(accessLogs()).isEqualTo(before);
    }

    @Test
    void 정상_로그인한_CSRF_403은_경고_한_줄이고_user_id가_붙고_요청이_끝나면_MDC에서_지워진다(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);
        long before = accessLogs();

        MvcResult r = mvc.perform(post("/api/v1/cases/CASE-2022-000001/escalate").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("CSRF_INVALID");
        assertDeniedLine(output, "CSRF_INVALID", "POST", "/api/v1/cases/CASE-2022-000001/escalate", userId(login));
        assertThat(accessLogs()).isEqualTo(before);
        assertThat(MDC.get("user_id")).isNull();
    }

    @Test
    void 정상_도메인_403은_경고_한_줄이고_예외_메시지는_로그에_없다(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);
        long before = accessLogs();

        MvcResult r = mvc.perform(get("/api/v1/_denied/domain").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("NOT_APPROVER");
        assertThat(json(r).path("error").path("message").asText()).contains("현재 역할");   // 응답 본문은 그대로
        assertDeniedLine(output, "NOT_APPROVER", "GET", "/api/v1/_denied/domain", userId(login));
        assertThat(output.getAll()).doesNotContain("현재 역할");
        assertThat(accessLogs()).isEqualTo(before);
    }

    @Test
    void 경계_익명_CSRF_403은_경고가_찍히고_user_id_칸은_비어_있다(CapturedOutput output) throws Exception {
        long before = accessLogs();

        MvcResult r = mvc.perform(post("/api/v1/cases/CASE-2022-000001/escalate")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("CSRF_INVALID");
        assertDeniedLine(output, "CSRF_INVALID", "POST", "/api/v1/cases/CASE-2022-000001/escalate", "");
        assertThat(accessLogs()).isEqualTo(before);
    }

    @Test
    void 경계_컨트롤러_안_AccessDeniedException은_경고가_한_번만이고_메시지는_로그에_없다(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);
        long before = accessLogs();

        MvcResult r = mvc.perform(get("/api/v1/_denied/mvc").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("FORBIDDEN");
        assertDeniedLine(output, "FORBIDDEN", "GET", "/api/v1/_denied/mvc", userId(login));
        assertThat(output.getAll()).doesNotContain(MVC_DENIED_MESSAGE);
        assertThat(accessLogs()).isEqualTo(before);
    }

    // --- 안 찍히는 것 · 회귀 (develop 에서도 통과) -------------------------------

    @Test
    void 실패_URL_403은_쿼리스트링을_찍지_않는다(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);

        MvcResult r = mvc.perform(get("/api/v1/admin/batches?secret=query-zz9").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(output.getAll()).doesNotContain("query-zz9");
    }

    @Test
    void 실패_CSRF_403은_쿼리스트링_본문_토큰_쿠키_값을_찍지_않는다(CapturedOutput output) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/cases/CASE-2022-000001/escalate?secret=query-zz9")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"body-zz9\"}")
                .header("X-XSRF-TOKEN", "header-token-zz9")
                .cookie(new Cookie("XSRF-TOKEN", "cookie-token-zz9"), new Cookie("SESSION", "session-zz9"))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(r)).isEqualTo("CSRF_INVALID");
        assertThat(output.getAll())
                .doesNotContain("query-zz9").doesNotContain("body-zz9")
                .doesNotContain("header-token-zz9").doesNotContain("cookie-token-zz9").doesNotContain("session-zz9");
    }

    @Test
    void 회귀_409_도메인_예외는_예전_문구_그대로이고_거부_경고가_아니다(CapturedOutput output) throws Exception {
        MvcResult login = login("inv01", PASSWORD);

        MvcResult r = mvc.perform(get("/api/v1/_denied/conflict").session(session(login))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(output.getAll()).contains("domain rule violated: PROBE_CONFLICT - probe-conflict-message");
        assertThat(deniedLines(output)).isEmpty();
    }

    @Test
    void 회귀_로그인_실패_401은_거부_경고가_없다(CapturedOutput output) throws Exception {
        MvcResult r = login("inv01", "wrong-password");

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(deniedLines(output)).isEmpty();
    }

    @Test
    void 회귀_비로그인_GET_401은_거부_경고가_없다(CapturedOutput output) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/admin/batches")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
        assertThat(deniedLines(output)).isEmpty();
    }

    // --- 도우미 -------------------------------------------------------------

    /** 경고가 정확히 한 줄, 모양은 `WARN [request_id] [user_id] … - access denied code=… method=… uri=…`(뒤에 아무것도 없음). */
    private void assertDeniedLine(CapturedOutput output, String code, String method, String uri, String userId) {
        List<String> lines = deniedLines(output);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("WARN");
        assertThat(lines.get(0)).endsWith("access denied code=" + code + " method=" + method + " uri=" + uri);
        assertThat(lines.get(0)).containsPattern("\\[[0-9a-f-]{36}] \\[" + userId + "] ");
    }

    private List<String> deniedLines(CapturedOutput output) {
        return output.getAll().lines().filter(l -> l.contains("access denied")).toList();
    }

    private long accessLogs() {
        return jdbc.queryForObject("select count(*) from ops.access_logs", Long.class);
    }

    private MvcResult login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", password)))).andReturn();
    }

    private static MockHttpSession session(MvcResult r) {
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String userId(MvcResult login) throws Exception {
        return json(login).path("id").asText();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }
}