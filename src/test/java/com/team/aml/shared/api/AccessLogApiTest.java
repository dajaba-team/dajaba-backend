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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/v1/admin/access-logs (SCRUM-168) — 실제 PostgreSQL + 필터 체인 전체.
 * 로그인 때 생기는 행과 섞이지 않게 테스트마다 먼 과거의 한 해(year())를 기간으로 쓰거나 새 사용자로 거른다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AccessLogApiTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessLogApiTest {

    static final String PASSWORD = "test-access-logs-pass-1234";
    static final String URL = "/api/v1/admin/access-logs";
    private static final AtomicInteger SEQ = new AtomicInteger(1900);

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
        MvcResult r = call(admin(), "size", "5");

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
            MvcResult r = mvc.perform(get(URL).session(session(login(loginId)))).andReturn();
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

    // --- 응답 모양 ----------------------------------------------------------------

    @Test
    void 정상_한_행은_8칸뿐이고_ip와_user_id는_없고_created_at은_초까지_Z다() throws Exception {
        String y = year();
        User actor = newUser();
        Case c = newCase();
        long id = log(actor.id, null, "CASE", c.id, "VIEW", "10.1.2.3", y + "-03-04T05:06:07.891234Z");

        ObjectNode item = (ObjectNode) json(call(admin(), period(y))).path("items").get(0);

        Set<String> keys = new HashSet<>();
        item.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("id", "login_id", "display_name", "login_id_attempted",
                "action", "target_type", "target_id", "created_at");
        assertThat(item).isEqualTo(om.readTree("""
                {"id":%d,"login_id":"%s","display_name":"%s","login_id_attempted":null,"action":"VIEW",
                 "target_type":"CASE","target_id":"%s","created_at":"%s-03-04T05:06:07Z"}
                """.formatted(id, actor.loginId, actor.displayName, c.caseNo, y)));
    }

    @Test
    void 정상_target_id는_네_종류_모두_화면_번호이고_로그인_행은_대상이_null() throws Exception {
        String y = year();
        User actor = newUser();
        User target = newUser();
        Case c = newCase();
        long draftDbId = 123;
        log(actor.id, null, "CASE", c.id, "VIEW", null, y + "-01-01T00:00:01Z");
        log(actor.id, null, "STR_DRAFT", draftDbId, "STR_VIEW", null, y + "-01-01T00:00:02Z");
        log(actor.id, null, "ALERT", c.alertId, "VIEW", null, y + "-01-01T00:00:03Z");
        log(actor.id, null, "USER", target.id, "ROLE_CHANGED", null, y + "-01-01T00:00:04Z");
        log(actor.id, null, null, null, "LOGIN_OK", "10.0.0.1", y + "-01-01T00:00:05Z");

        JsonNode items = json(call(admin(), period(y, "sort", "created_at,asc"))).path("items");

        assertThat(items).hasSize(5);
        assertThat(targets(items)).containsExactly(
                "CASE " + c.caseNo, "STR_DRAFT DRF-000123", "ALERT " + c.alertNo, "USER " + target.loginId,
                "null null");
        assertThat(items.get(4).path("target_type").isNull()).isTrue();
        assertThat(items.get(4).path("target_id").isNull()).isTrue();
    }

    @Test
    void 경계_STR_DRAFT인데_target_id가_null이면_DRF_000000이_아니라_null() throws Exception {
        String y = year();
        log(newUser().id, null, "STR_DRAFT", null, "STR_VIEW", null, y + "-01-01T00:00:01Z");

        JsonNode item = json(call(admin(), period(y))).path("items").get(0);

        assertThat(item.path("target_type").asText()).isEqualTo("STR_DRAFT");
        assertThat(item.path("target_id").isNull()).isTrue();
    }

    @Test
    void 정상_행위자는_VIEW에_채워지고_LOGIN_FAIL은_비고_입력한_아이디만() throws Exception {
        String y = year();
        User actor = newUser();
        log(actor.id, null, null, null, "LOGOUT", null, y + "-01-01T00:00:01Z");
        log(null, "nobody-typed", null, null, "LOGIN_FAIL", "10.0.0.2", y + "-01-01T00:00:02Z");

        JsonNode items = json(call(admin(), period(y, "sort", "created_at,asc"))).path("items");

        assertThat(items.get(0).path("login_id").asText()).isEqualTo(actor.loginId);
        assertThat(items.get(0).path("display_name").asText()).isEqualTo(actor.displayName);
        assertThat(items.get(0).path("login_id_attempted").isNull()).isTrue();
        assertThat(items.get(1).path("action").asText()).isEqualTo("LOGIN_FAIL");
        assertThat(items.get(1).path("login_id").isNull()).isTrue();
        assertThat(items.get(1).path("display_name").isNull()).isTrue();
        assertThat(items.get(1).path("login_id_attempted").asText()).isEqualTo("nobody-typed");
    }

    // --- 필터 ---------------------------------------------------------------------

    @Test
    void 정상_login_id는_그_사람이_행위자인_줄만이고_LOGIN_FAIL_입력_아이디는_안_잡힌다() throws Exception {
        String y = year();
        User a = newUser();
        User b = newUser();
        long mine = log(a.id, null, null, null, "LOGIN_OK", null, y + "-01-01T00:00:01Z");
        log(b.id, null, null, null, "LOGIN_OK", null, y + "-01-01T00:00:02Z");
        log(null, a.loginId, null, null, "LOGIN_FAIL", null, y + "-01-01T00:00:03Z");

        assertThat(ids(call(admin(), period(y, "login_id", a.loginId)))).containsExactly(mine);
    }

    @Test
    void 정상_action과_target_type만으로_거른다() throws Exception {
        String y = year();
        User u = newUser();
        Case c = newCase();
        long view = log(u.id, null, "CASE", c.id, "VIEW", null, y + "-01-01T00:00:01Z");
        long export = log(u.id, null, "CASE", c.id, "EXPORT", null, y + "-01-01T00:00:02Z");
        long alertView = log(u.id, null, "ALERT", c.alertId, "VIEW", null, y + "-01-01T00:00:03Z");

        assertThat(ids(call(admin(), period(y, "action", "VIEW")))).containsExactly(alertView, view);
        assertThat(ids(call(admin(), period(y, "target_type", "CASE")))).containsExactly(export, view);
        assertThat(ids(call(admin(), period(y, "action", "VIEW", "target_type", "CASE")))).containsExactly(view);
    }

    @Test
    void 정상_target_type과_화면_번호_target_id는_네_종류_각각_그_줄만() throws Exception {
        String y = year();
        User u = newUser();
        User target = newUser();
        User other = newUser();
        Case c = newCase();
        Case c2 = newCase();
        long caseRow = log(u.id, null, "CASE", c.id, "VIEW", null, y + "-01-01T00:00:01Z");
        log(u.id, null, "CASE", c2.id, "VIEW", null, y + "-01-01T00:00:02Z");
        long draftRow = log(u.id, null, "STR_DRAFT", 77, "STR_VIEW", null, y + "-01-01T00:00:03Z");
        log(u.id, null, "STR_DRAFT", 78, "STR_VIEW", null, y + "-01-01T00:00:04Z");
        long alertRow = log(u.id, null, "ALERT", c.alertId, "VIEW", null, y + "-01-01T00:00:05Z");
        log(u.id, null, "ALERT", c2.alertId, "VIEW", null, y + "-01-01T00:00:06Z");
        long userRow = log(u.id, null, "USER", target.id, "USER_CREATED", null, y + "-01-01T00:00:07Z");
        log(u.id, null, "USER", other.id, "USER_CREATED", null, y + "-01-01T00:00:08Z");

        assertThat(ids(call(admin(), period(y, "target_type", "CASE", "target_id", c.caseNo)))).containsExactly(caseRow);
        assertThat(ids(call(admin(), period(y, "target_type", "STR_DRAFT", "target_id", "DRF-000077"))))
                .containsExactly(draftRow);
        assertThat(ids(call(admin(), period(y, "target_type", "ALERT", "target_id", c.alertNo)))).containsExactly(alertRow);
        assertThat(ids(call(admin(), period(y, "target_type", "USER", "target_id", target.loginId))))
                .containsExactly(userRow);
    }

    @Test
    void 정상_from은_포함_to는_제외이고_다른_필터와_AND() throws Exception {
        String y = year();
        User a = newUser();
        User b = newUser();
        long before = log(a.id, null, null, null, "LOGIN_OK", null, y + "-05-31T23:59:59Z");
        long atFrom = log(a.id, null, null, null, "LOGIN_OK", null, y + "-06-01T00:00:00Z");
        long otherUser = log(b.id, null, null, null, "LOGIN_OK", null, y + "-06-01T00:00:01Z");
        long atTo = log(a.id, null, null, null, "LOGIN_OK", null, y + "-06-02T00:00:00Z");
        String from = y + "-06-01T00:00:00Z";
        String to = y + "-06-02T00:00:00Z";

        assertThat(ids(call(admin(), "from", from, "to", to))).containsExactly(otherUser, atFrom);
        assertThat(ids(call(admin(), "from", from, "to", to, "login_id", a.loginId))).containsExactly(atFrom);
        assertThat(ids(call(admin(), "from", y + "-06-02T00:00:00Z", "to", y + "-06-03T00:00:00Z"))).containsExactly(atTo);
        assertThat(ids(call(admin(), "from", y + "-05-31T00:00:00Z", "to", from))).containsExactly(before);
    }

    // --- 정렬 ---------------------------------------------------------------------

    @Test
    void 정상_기본은_최신순이고_같은_시각은_id로_desc_asc_둘_다() throws Exception {
        String y = year();
        User u = newUser();
        long old = log(u.id, null, null, null, "LOGIN_OK", null, y + "-01-01T00:00:00Z");
        long same1 = log(u.id, null, null, null, "LOGIN_OK", null, y + "-01-02T00:00:00Z");
        long same2 = log(u.id, null, null, null, "LOGOUT", null, y + "-01-02T00:00:00Z");

        assertThat(ids(call(admin(), period(y)))).containsExactly(same2, same1, old);
        assertThat(ids(call(admin(), period(y, "sort", "created_at,desc")))).containsExactly(same2, same1, old);
        assertThat(ids(call(admin(), period(y, "sort", "created_at,asc")))).containsExactly(old, same1, same2);
    }

    // --- 경계 · 없는 값 → 200 빈 목록 ---------------------------------------------------

    @Test
    void 경계_size_100은_200() throws Exception {
        assertThat(call(admin(), "size", "100").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 경계_형식은_맞지만_없는_번호와_없는_login_id는_200_빈_items() throws Exception {
        String y = year();
        User u = newUser();
        Case c = newCase();
        log(u.id, null, "CASE", c.id, "VIEW", null, y + "-01-01T00:00:01Z");

        assertThat(ids(call(admin(), period(y, "target_type", "CASE", "target_id", "CASE-1999-999999")))).isEmpty();
        assertThat(ids(call(admin(), period(y, "target_type", "STR_DRAFT", "target_id", "DRF-999999")))).isEmpty();
        assertThat(ids(call(admin(), period(y, "target_type", "ALERT", "target_id", "ALT-19990101-999999")))).isEmpty();
        assertThat(ids(call(admin(), period(y, "target_type", "USER", "target_id", "no-such-user")))).isEmpty();
        assertThat(ids(call(admin(), period(y, "login_id", "no-such-user")))).isEmpty();
    }

    @Test
    void 경계_이_목록을_불러도_access_logs_행_수가_그대로() throws Exception {
        MockHttpSession admin = admin();
        long before = jdbc.queryForObject("select count(*) from ops.access_logs", Long.class);

        call(admin, "size", "5");
        call(admin, "action", "LOGIN_OK");

        assertThat(jdbc.queryForObject("select count(*) from ops.access_logs", Long.class)).isEqualTo(before);
    }

    // --- 잘못된 값 → 400 INVALID_REQUEST ------------------------------------------

    @Test
    void 실패_size_101은_400() throws Exception {
        assertInvalid(call(admin(), "size", "101"), "size");
    }

    @Test
    void 실패_target_type과_번호_형식이_다르면_400이고_details는_target_id() throws Exception {
        MvcResult r = call(admin(), "target_type", "CASE", "target_id", "DRF-000001");

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r)).isEqualTo(om.readTree("""
                {"error":{"code":"INVALID_REQUEST","message":"요청 값이 올바르지 않습니다",
                          "details":{"target_id":"CASE 번호 형식이 아닙니다 (예: CASE-2022-000057)"}}}
                """));
    }

    @Test
    void 실패_종류별로_번호_형식이_틀리면_400_target_id() throws Exception {
        Map<String, String> wrong = Map.of("STR_DRAFT", "CASE-2022-000057", "ALERT", "ALT-2022-000001",
                "USER", "a", "CASE", "CASE-22-000057");
        for (var e : wrong.entrySet()) {
            MvcResult r = call(admin(), "target_type", e.getKey(), "target_id", e.getValue());
            assertInvalid(r, e.getKey(), "target_id");
        }
    }

    @Test
    void 실패_target_id만_오면_400() throws Exception {
        assertInvalid(call(admin(), "target_id", "CASE-2022-000057"), "target_id만", "target_id_with_type");
    }

    @Test
    void 실패_없는_action은_400() throws Exception {
        assertInvalid(call(admin(), "action", "ACCESS_DENIED"), "action", "action");
    }

    @Test
    void 실패_login_id_형식이_틀리면_400() throws Exception {
        assertInvalid(call(admin(), "login_id", "a"), "login_id", "login_id");
    }

    @Test
    void 실패_기간_오류는_400이고_details_키는_AI_호출_기록과_같다() throws Exception {
        for (String name : List.of("from", "to")) {
            for (String value : List.of("2007-01-02T00:00:00+09:00", "20261108")) {
                assertInvalid(call(admin(), name, value), name + "=" + value, name);
            }
        }
        assertInvalid(call(admin(), "from", "2026-02-30T00:00:00Z"), "2월 30일", "real_dates");
        assertInvalid(call(admin(), "from", "2005-01-03T00:00:00Z", "to", "2005-01-02T00:00:00Z"),
                "from > to", "period_in_order");
    }

    @Test
    void 실패_그_밖의_형식이_틀린_값은_400() throws Exception {
        for (String[] q : List.of(new String[]{"target_type", "BATCH_RUN"}, new String[]{"sort", "id,desc"},
                new String[]{"sort", "created_at"}, new String[]{"page", "-1"}, new String[]{"size", "0"})) {
            assertInvalid(call(admin(), q), q[0] + "=" + q[1]);
        }
    }

    // --- 도우미 -------------------------------------------------------------------

    private record User(long id, String loginId, String displayName) {}

    private record Case(long id, String caseNo, long alertId, String alertNo) {}

    /** 테스트마다 다른 해 — 그해 1/1~다음 해 1/1 기간으로 다른 테스트·로그인 행과 섞이지 않게. */
    private static String year() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    private static String[] period(String year, String... more) {
        List<String> all = new ArrayList<>(List.of("from", year + "-01-01T00:00:00Z",
                "to", (Integer.parseInt(year) + 1) + "-01-01T00:00:00Z"));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private User newUser() {
        String loginId = "al" + SEQ.incrementAndGet();
        String name = "이름 " + loginId;
        long id = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, 'INVESTIGATOR', 1, 1) returning id""", Long.class, loginId, name);
        return new User(id, loginId, name);
    }

    /** 경보 + 케이스 한 쌍. 엔티티 대신 SQL — CaseApiTest 와 같은 틀. */
    private Case newCase() {
        int n = SEQ.incrementAndGet();
        String alertNo = "ALT-20230201-%06d".formatted(n);
        String caseNo = "CASE-2023-%06d".formatted(n);
        String accountKey = "ACC-AL-" + n;
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-02-01', 'RUNNING', 1) returning id""", Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_AL_" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-02-01 00:00:00', '2023-02-01 23:59:59', '2023-02-01 23:59:59',
                        '{}'::jsonb, '2023-02-01')
                returning id""", Long.class, alertNo, batchId, ruleId, accountKey);
        long caseId = jdbc.queryForObject("""
                insert into ops.cases (case_no, alert_id, account_key, status, created_by, updated_by)
                values (?, ?, ?, 'OPEN', 1, 1) returning id""", Long.class, caseNo, alertId, accountKey);
        return new Case(caseId, caseNo, alertId, alertNo);
    }

    private long log(Long userId, String attempted, String targetType, Number targetId, String action, String ip,
                     String createdAt) {
        return jdbc.queryForObject("""
                insert into ops.access_logs (user_id, login_id_attempted, target_type, target_id, action, ip, created_at)
                values (?, ?, ?, ?, ?, ?::inet, ?) returning id
                """, Long.class, userId, attempted, targetType, targetId == null ? null : targetId.longValue(),
                action, ip, Timestamp.from(Instant.parse(createdAt)));
    }

    private static List<String> targets(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.path("target_type").asText("null") + " " + i.path("target_id").asText("null")));
        return out;
    }

    private void assertInvalid(MvcResult r, String label) throws Exception {
        assertThat(r.getResponse().getStatus()).as(label).isEqualTo(400);
        assertThat(code(r)).as(label).isEqualTo("INVALID_REQUEST");
    }

    private void assertInvalid(MvcResult r, String label, String detailsKey) throws Exception {
        assertInvalid(r, label);
        assertThat(json(r).path("error").path("details").has(detailsKey))
                .as(label + " details=" + json(r).path("error").path("details")).isTrue();
    }

    private List<Long> ids(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        json(r).path("items").forEach(i -> ids.add(i.path("id").asLong()));
        return ids;
    }

    /** 이름·값 짝으로 보낸다 — .param 은 값을 다시 인코딩하지 않아 '+09:00' 원문이 간다 (SCRUM-204). */
    private MvcResult call(MockHttpSession session, String... nameValues) throws Exception {
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
