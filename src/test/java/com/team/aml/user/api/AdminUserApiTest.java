package com.team.aml.user.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/v1/admin/users (SCRUM-135 ①) — 실제 PostgreSQL + 필터 체인 전체.
 * 이 테스트가 넣는 사용자는 login_id 앞에 테스트마다 다른 접두어를 붙여 다른 행과 섞이지 않게 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AdminUserApiTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserApiTest {

    static final String PASSWORD = "test-admin-users-pass-1234";
    static final String URL = "/api/v1/admin/users";

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    // --- 응답 모양 ----------------------------------------------------------------

    @Test
    void 정상_ADMIN은_200이고_항목_칸은_정확히_여섯이며_active_case_count는_null() throws Exception {
        MvcResult r = call(admin(), URL + "?size=5");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(5);
        assertThat(body.has("total")).isTrue();
        assertThat(body.path("items")).isNotEmpty();
        for (JsonNode item : body.path("items")) {
            List<String> keys = new ArrayList<>();
            item.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrder(
                    "id", "login_id", "display_name", "role", "is_active", "active_case_count");
            assertThat(item.path("is_active").isBoolean()).isTrue();
            assertThat(item.get("active_case_count").isNull()).isTrue();
        }
    }

    @Test
    void 보안_응답_어디에도_password_글자가_없다() throws Exception {
        for (String query : List.of("", "?is_active=false", "?size=100")) {
            MvcResult r = call(admin(), URL + query);
            assertThat(r.getResponse().getStatus()).as(query).isEqualTo(200);
            String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(raw.toLowerCase()).as(query).doesNotContain("password");
        }
    }

    // --- is_active ----------------------------------------------------------------

    @Test
    void 정상_is_active를_안_보내면_활성만이고_false면_비활성만() throws Exception {
        String p = prefix();
        long active = insertUser(p + "on", "활성 사람", "APPROVER", true);
        long inactive = insertUser(p + "off", "퇴사자", "APPROVER", false);

        JsonNode byDefault = json(call(admin(), URL + "?size=100"));
        assertThat(ids(byDefault, p)).containsExactly(active);
        byDefault.path("items").forEach(i -> assertThat(i.path("is_active").asBoolean()).isTrue());

        JsonNode off = json(call(admin(), URL + "?is_active=false&size=100"));
        assertThat(ids(off, p)).containsExactly(inactive);
        off.path("items").forEach(i -> assertThat(i.path("is_active").asBoolean()).isFalse());
    }

    @Test
    void 정상_SYSTEM은_비활성이라_is_active_false일_때_role_SYSTEM으로_나온다() throws Exception {
        JsonNode body = json(call(admin(), URL + "?role=SYSTEM&is_active=false"));

        assertThat(body.path("total").asLong()).isEqualTo(1);
        JsonNode system = body.path("items").get(0);
        assertThat(system.path("id").asLong()).isEqualTo(1L);
        assertThat(system.path("role").asText()).isEqualTo("SYSTEM");
        assertThat(system.path("is_active").asBoolean()).isFalse();

        assertThat(json(call(admin(), URL + "?role=SYSTEM")).path("total").asLong()).isZero();
    }

    // --- role · 정렬 ---------------------------------------------------------------

    @Test
    void 정상_조사역_활성_100은_조사역만이고_display_name_오름차순_같으면_id순() throws Exception {
        String p = prefix();
        long na = insertUser(p + "a", "ㅎ 나중", "INVESTIGATOR", true);
        long same1 = insertUser(p + "b", "ㄱ 같은이름", "INVESTIGATOR", true);
        long same2 = insertUser(p + "c", "ㄱ 같은이름", "INVESTIGATOR", true);
        insertUser(p + "apr", "ㄱ 승인자", "APPROVER", true);
        insertUser(p + "off", "ㄱ 퇴사 조사역", "INVESTIGATOR", false);

        JsonNode body = json(call(admin(), URL + "?role=INVESTIGATOR&is_active=true&size=100"));

        assertThat(body.path("size").asInt()).isEqualTo(100);
        body.path("items").forEach(i -> {
            assertThat(i.path("role").asText()).isEqualTo("INVESTIGATOR");
            assertThat(i.path("is_active").asBoolean()).isTrue();
        });
        assertThat(ids(body, p)).containsExactly(same1, same2, na);
        assertThat(body.path("total").asLong()).isEqualTo(body.path("items").size());
    }

    @Test
    void 경계_sort를_보내도_무시하고_고정_정렬이다() throws Exception {
        String p = prefix();
        long z = insertUser(p + "z", "ㅎ 끝", "APPROVER", true);
        long a = insertUser(p + "a", "ㄱ 처음", "APPROVER", true);

        MvcResult r = call(admin(), URL + "?role=APPROVER&size=100&sort=display_name,desc");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(json(r), p)).containsExactly(a, z);
    }

    @Test
    void 정상_page_size로_나눠_받는다() throws Exception {
        String p = prefix();
        insertUser(p + "1", "ㄱ 페이지", "APPROVER", true);
        insertUser(p + "2", "ㄱ 페이지", "APPROVER", true);

        JsonNode all = json(call(admin(), URL + "?role=APPROVER&size=100"));
        JsonNode second = json(call(admin(), URL + "?role=APPROVER&page=1&size=1"));

        assertThat(second.path("page").asInt()).isEqualTo(1);
        assertThat(second.path("size").asInt()).isEqualTo(1);
        assertThat(second.path("total").asLong()).isEqualTo(all.path("total").asLong());
        assertThat(second.path("items").get(0).path("id")).isEqualTo(all.path("items").get(1).path("id"));
    }

    // --- 잘못된 값 → 400 INVALID_REQUEST ------------------------------------------

    @Test
    void 경계_size_100은_200() throws Exception {
        assertThat(call(admin(), URL + "?size=100").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 실패_형식이_틀린_값은_400이고_details_키는_snake_case() throws Exception {
        Map<String, String> cases = Map.of(
                "size=101", "size",
                "size=0", "size",
                "page=-1", "page",
                "role=FOO", "role",
                "is_active=maybe", "is_active");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            MvcResult r = call(admin(), URL + "?" + c.getKey());
            assertThat(r.getResponse().getStatus()).as(c.getKey()).isEqualTo(400);
            JsonNode error = json(r).path("error");
            assertThat(error.path("code").asText()).as(c.getKey()).isEqualTo("INVALID_REQUEST");
            Set<String> keys = new HashSet<>();
            error.path("details").fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as(c.getKey()).containsExactly(c.getValue());
        }
    }

    // --- 권한 -------------------------------------------------------------------

    @Test
    void 실패_INVESTIGATOR와_APPROVER는_403() throws Exception {
        for (String loginId : List.of("inv01", "apr01")) {
            MvcResult r = call(session(login(loginId)), URL);
            assertThat(r.getResponse().getStatus()).as(loginId).isEqualTo(403);
            assertThat(json(r).path("error").path("code").asText()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void 실패_비로그인은_401() throws Exception {
        MvcResult r = mvc.perform(get(URL)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(r).path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    // --- 도우미 -------------------------------------------------------------------

    private long insertUser(String loginId, String displayName, String role, boolean active) {
        return jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, password_hash, is_active, created_by, updated_by)
                values (?, ?, ?, null, ?, 1, 1) returning id
                """, Long.class, loginId, displayName, role, active);
    }

    private static String prefix() {
        return "t135-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    /** 응답 순서 그대로, 이 테스트가 넣은(접두어) 행의 id 만. */
    private static List<Long> ids(JsonNode body, String prefix) {
        List<Long> ids = new ArrayList<>();
        body.path("items").forEach(i -> {
            if (i.path("login_id").asText().startsWith(prefix)) ids.add(i.path("id").asLong());
        });
        return ids;
    }

    private MvcResult call(MockHttpSession session, String url) throws Exception {
        return mvc.perform(get(url).session(session)).andReturn();
    }

    private MockHttpSession admin() throws Exception {
        return session(login("admin"));
    }

    private MvcResult login(String loginId) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", PASSWORD)))).andReturn();
    }

    private static MockHttpSession session(MvcResult r) {
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
