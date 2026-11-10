package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/v1/admin/ai-calls (SCRUM-134) — 실제 PostgreSQL + 필터 체인 전체.
 * 다른 테스트의 행과 섞이지 않게 테스트마다 다른 endpoint 이름이나 먼 과거 기간을 쓴다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AiCallLogApiTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiCallLogApiTest {

    static final String PASSWORD = "test-ai-calls-pass-1234";
    static final String URL = "/api/v1/admin/ai-calls";

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

    // --- 권한 -------------------------------------------------------------------

    @Test
    void 정상_ADMIN은_200이고_PageResponse_모양이다() throws Exception {
        MvcResult r = call(admin(), URL + "?size=5");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.has("items")).isTrue();
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(5);
        assertThat(body.has("total")).isTrue();
    }

    @Test
    void 실패_INVESTIGATOR와_APPROVER는_403() throws Exception {
        for (String loginId : List.of("inv01", "apr01")) {
            MvcResult r = call(session(login(loginId)), URL);
            assertThat(r.getResponse().getStatus()).as(loginId).isEqualTo(403);
            assertThat(code(r)).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void 실패_비로그인은_401() throws Exception {
        MvcResult r = mvc.perform(get(URL)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
    }

    // --- 응답 모양 · 정렬 ---------------------------------------------------------

    @Test
    void 정상_응답_한_행은_snake_case_칸이고_created_at은_초까지_Z다() throws Exception {
        String ep = endpoint();
        insert(ep, "req-shape", 2, 503, "LLM_UNAVAILABLE", 1234, "v9", "gen-1", "DRAFT", "DRF-000123",
                "2001-02-03T04:05:06.789123Z");

        ObjectNode item = (ObjectNode) json(call(admin(), URL + "?endpoint=" + ep)).path("items").get(0);

        assertThat(item.remove("id").isIntegralNumber()).isTrue();
        assertThat(item).isEqualTo(om.readTree("""
                {"endpoint":"%s","request_id":"req-shape","attempt":2,"status_code":503,
                 "error_code":"LLM_UNAVAILABLE","duration_ms":1234,"ai_version":"v9","generation_id":"gen-1",
                 "target_type":"DRAFT","target_id":"DRF-000123","created_at":"2001-02-03T04:05:06Z"}
                """.formatted(ep)));
    }

    @Test
    void 정상_기본_정렬은_최신순이고_같은_시각은_id_내림차순이다() throws Exception {
        String ep = endpoint();
        long older = insert(ep, "a", 1, 200, null, 1, null, null, null, null, "2002-01-01T00:00:00Z");
        long sameA = insert(ep, "b", 1, 200, null, 1, null, null, null, null, "2002-01-02T00:00:00Z");
        long sameB = insert(ep, "c", 1, 200, null, 1, null, null, null, null, "2002-01-02T00:00:00Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep))).containsExactly(sameB, sameA, older);
        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&sort=created_at,asc"))).containsExactly(older, sameA, sameB);
    }

    // --- 필터 -------------------------------------------------------------------

    @Test
    void 정상_endpoint_request_id_필터는_맞는_행만이고_재시도_두_행은_다_나온다() throws Exception {
        String ep = endpoint();
        String rid = UUID.randomUUID().toString();
        long first = insert(ep, rid, 1, 503, "DB_UNAVAILABLE", 10, null, null, "CASE", "CASE-1", "2003-01-01T00:00:00Z");
        long retry = insert(ep, rid, 2, 200, null, 20, "p1", "g1", "CASE", "CASE-1", "2003-01-01T00:00:01Z");
        insert(ep, "other", 1, 200, null, 1, null, null, "CASE", "CASE-1", "2003-01-01T00:00:02Z");
        insert(endpoint(), rid + "x", 1, 200, null, 1, null, null, null, null, "2003-01-01T00:00:03Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep))).hasSize(3);
        MvcResult r = call(admin(), URL + "?request_id=" + rid);
        assertThat(ids(r)).containsExactly(retry, first);
        assertThat(json(r).path("total").asLong()).isEqualTo(2);
    }

    @Test
    void 정상_target_type_target_id_필터는_둘_다_맞는_행만() throws Exception {
        String ep = endpoint();
        long hit = insert(ep, "t1", 1, 200, null, 1, null, null, "DRAFT", "DRF-1", "2004-01-01T00:00:00Z");
        long otherId = insert(ep, "t2", 1, 200, null, 1, null, null, "DRAFT", "DRF-2", "2004-01-01T00:00:01Z");
        insert(ep, "t3", 1, 200, null, 1, null, null, "CASE", "DRF-1", "2004-01-01T00:00:02Z");
        insert(ep, "t4", 1, 200, null, 1, null, null, null, null, "2004-01-01T00:00:03Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&target_type=DRAFT&target_id=DRF-1"))).containsExactly(hit);
        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&target_type=DRAFT"))).containsExactly(otherId, hit);
    }

    @Test
    void 경계_from은_포함하고_to는_제외한다() throws Exception {
        String ep = endpoint();
        insert(ep, "f0", 1, 200, null, 1, null, null, null, null, "2005-01-01T00:00:00Z");
        long atFrom = insert(ep, "f1", 1, 200, null, 1, null, null, null, null, "2005-01-02T00:00:00Z");
        long inside = insert(ep, "f2", 1, 200, null, 1, null, null, null, null, "2005-01-02T12:00:00Z");
        insert(ep, "f3", 1, 200, null, 1, null, null, null, null, "2005-01-03T00:00:00Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&from=2005-01-02T00:00:00Z&to=2005-01-03T00:00:00Z")))
                .containsExactly(inside, atFrom);
    }

    @Test
    void 경계_outcome_FAILURE는_타임아웃_null과_200_AI_BAD_RESPONSE를_잡고_SUCCESS는_error_code_없는_행만() throws Exception {
        String ep = endpoint();
        long ok = insert(ep, "o1", 1, 200, null, 1, "v", "g", null, null, "2006-01-01T00:00:00Z");
        long timeout = insert(ep, "o2", 1, null, "AI_TIMEOUT", 90000, null, null, null, null, "2006-01-01T00:00:01Z");
        long emptyBody = insert(ep, "o3", 1, 200, "AI_BAD_RESPONSE", 5, null, null, null, null, "2006-01-01T00:00:02Z");
        long client = insert(ep, "o4", 1, 422, "VALIDATION_ERROR", 5, null, null, null, null, "2006-01-01T00:00:03Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&outcome=SUCCESS"))).containsExactly(ok);
        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&outcome=FAILURE"))).containsExactly(client, emptyBody, timeout);
        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&error_code=AI_TIMEOUT"))).containsExactly(timeout);
    }

    @Test
    void 경계_맞는_행이_없으면_200_빈_items() throws Exception {
        MvcResult r = call(admin(), URL + "?endpoint=" + endpoint());

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).path("items").isArray()).isTrue();
        assertThat(json(r).path("items")).isEmpty();
        assertThat(json(r).path("total").asLong()).isZero();
    }

    @Test
    void 경계_size_100은_200이고_101은_400() throws Exception {
        assertThat(call(admin(), URL + "?size=100").getResponse().getStatus()).isEqualTo(200);
        assertInvalid(call(admin(), URL + "?size=101"));
    }

    @Test
    void 경계_시각은_Z만_받고_오프셋은_400() throws Exception {
        String ep = endpoint();
        long hit = insert(ep, "z1", 1, 200, null, 1, null, null, null, null, "2007-01-01T15:00:00Z");

        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&from=2007-01-01T15:00:00Z"))).containsExactly(hit);
        assertThat(ids(call(admin(), URL + "?endpoint=" + ep + "&from=2007-01-01T15:00:00.000Z"))).containsExactly(hit);
    }

    @Test
    void 경계_from과_to가_같은_시각이면_200_빈_items() throws Exception {
        String ep = endpoint();
        insert(ep, "same", 1, 200, null, 1, null, null, null, null, "2007-02-01T00:00:00Z");

        assertThat(ids(callParams(admin(), "endpoint", ep, "from", "2007-02-01T00:00:00Z", "to", "2007-02-01T00:00:00Z")))
                .isEmpty();
    }

    @Test
    void 실패_시각이_Z가_아니면_400이고_details에_칸_이름() throws Exception {
        // .param 은 값을 다시 인코딩하지 않아 서버가 '+09:00' 원문을 받는다 (SCRUM-204).
        for (String name : List.of("from", "to")) {
            for (String value : List.of("2007-01-02T00:00:00+09:00", "20261108", "2026-13-40T00:00:00Z")) {
                MvcResult r = callParams(admin(), name, value);
                assertInvalid(r, name + "=" + value);
                assertThat(json(r).path("error").path("details").has(name)).as(name + "=" + value).isTrue();
            }
        }
    }

    @Test
    void 실패_패턴은_맞지만_없는_날짜는_500이_아니라_400() throws Exception {
        MvcResult feb30 = callParams(admin(), "from", "2026-02-30T00:00:00Z");
        assertInvalid(feb30, "from");
        assertThat(json(feb30).path("error").path("details").has("real_dates")).isTrue();   // isRealDates 이름 그대로
        assertInvalid(callParams(admin(), "to", "2026-04-31T00:00:00Z"), "to");
    }

    // --- 잘못된 값 → 400 INVALID_REQUEST ------------------------------------------

    @Test
    void 실패_from이_to보다_늦으면_400() throws Exception {
        assertInvalid(call(admin(), URL + "?from=2005-01-03T00:00:00Z&to=2005-01-02T00:00:00Z"));
    }

    @Test
    void 실패_target_id만_오면_400() throws Exception {
        assertInvalid(call(admin(), URL + "?target_id=DRF-1"));
    }

    @Test
    void 실패_형식이_틀린_값은_400() throws Exception {
        for (String query : List.of("target_type=GENERATION", "outcome=OK", "sort=id,desc", "sort=created_at",
                "from=2005-01-02", "to=yesterday", "page=-1", "size=0", "endpoint=")) {
            assertInvalid(call(admin(), URL + "?" + query), query);
        }
    }

    // --- 도우미 -------------------------------------------------------------------

    private long insert(String endpoint, String requestId, int attempt, Integer status, String errorCode,
                        int durationMs, String aiVersion, String generationId, String targetType, String targetId,
                        String createdAt) {
        return jdbc.queryForObject("""
                insert into ops.ai_call_logs (endpoint, request_id, attempt, status_code, error_code, duration_ms,
                                              ai_version, generation_id, target_type, target_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, endpoint, requestId, attempt, status, errorCode, durationMs, aiVersion,
                generationId, targetType, targetId, Timestamp.from(Instant.parse(createdAt)));
    }

    private static String endpoint() {
        return "/t/" + UUID.randomUUID();
    }

    private void assertInvalid(MvcResult r) throws Exception {
        assertInvalid(r, "");
    }

    private void assertInvalid(MvcResult r, String label) throws Exception {
        assertThat(r.getResponse().getStatus()).as(label).isEqualTo(400);
        assertThat(code(r)).as(label).isEqualTo("INVALID_REQUEST");
    }

    private List<Long> ids(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        json(r).path("items").forEach(i -> ids.add(i.path("id").asLong()));
        return ids;
    }

    private MvcResult call(MockHttpSession session, String url) throws Exception {
        return mvc.perform(get(url).session(session)).andReturn();
    }

    /** 이름·값 짝으로 보낸다 — 값은 원문 그대로 서버에 간다. */
    private MvcResult callParams(MockHttpSession session, String... nameValues) throws Exception {
        var req = get(URL).session(session);
        for (int i = 0; i < nameValues.length; i += 2) req.param(nameValues[i], nameValues[i + 1]);
        return mvc.perform(req).andReturn();
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

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }
}
