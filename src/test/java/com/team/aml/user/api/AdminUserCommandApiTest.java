package com.team.aml.user.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계정 관리 쓰기 API (SCRUM-135 ②-a) — 발급·재활성화 + 관리자 본인·SYSTEM 막기.
 * 실제 PostgreSQL + 필터 체인 전체(세션·CSRF·maximumSessions). 사용자 흐름 기준으로 본다.
 * 이 테스트가 만드는 계정은 login_id 앞에 테스트마다 다른 접두어를 붙인다. 개발용 시드(admin 등)는 바꾸지 않는다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AdminUserCommandApiTest.SEED_PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminUserCommandApiTest {

    static final String SEED_PASSWORD = "test-admin-cmd-pass-1234";
    static final String URL = "/api/v1/admin/users";
    static final String ME = "/api/v1/auth/me";
    static final String PW = "first-pass-1234";
    static final long SYSTEM_ID = 1L;
    static final AtomicLong SEQ = new AtomicLong(700_000);

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
    @Autowired PlatformTransactionManager transactionManager;

    // --- 발급 -------------------------------------------------------------------

    @Test
    void 정상_발급은_201_한_줄이고_목록에_보이며_그_계정으로_로그인되고_USER_CREATED_한_행() throws Exception {
        MockHttpSession admin = admin();
        String loginId = prefix() + "inv";

        MvcResult r = create(admin, body(loginId, "새 조사역", "INVESTIGATOR", PW));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode row = json(r);
        assertThat(keys(row)).containsExactlyInAnyOrder(
                "id", "login_id", "display_name", "role", "is_active", "active_case_count");
        assertThat(row.path("login_id").asText()).isEqualTo(loginId);
        assertThat(row.path("display_name").asText()).isEqualTo("새 조사역");
        assertThat(row.path("role").asText()).isEqualTo("INVESTIGATOR");
        assertThat(row.path("is_active").asBoolean()).isTrue();
        assertThat(row.path("active_case_count").asLong()).isZero();
        long id = row.path("id").asLong();

        JsonNode list = json(mvc.perform(get(URL + "?role=INVESTIGATOR&size=100").session(admin)).andReturn());
        assertThat(list.path("items").findValuesAsText("login_id")).contains(loginId);
        assertThat(login(loginId, PW).getResponse().getStatus()).isEqualTo(200);
        assertThat(logs(id)).containsExactly(List.of("USER_CREATED", adminId()));   // target USER·대상 id·관리자 user_id
    }

    @Test
    void 경계_아이디_3자와_64자_이름_50자_앞뒤공백은_빼고_비밀번호_8자는_201() throws Exception {
        MockHttpSession admin = admin();
        String p = prefix();
        String sixtyFour = (p + "x".repeat(64)).substring(0, 64);
        String fifty = "가".repeat(50);

        assertThat(create(admin, body(uniqueThree(), "세 글자", "APPROVER", PW)).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(admin, body(sixtyFour, "육십사", "APPROVER", PW)).getResponse().getStatus()).isEqualTo(201);
        MvcResult named = create(admin, body(p + "name", "  " + fifty + "  ", "APPROVER", PW));
        assertThat(named.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(named).path("display_name").asText()).isEqualTo(fifty);
        assertThat(create(admin, body(p + "pw8", "여덟", "APPROVER", "12345678")).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void 경계_비밀번호는_72바이트까지_영문_72자_한글_24자는_201이고_73바이트부터_400() throws Exception {
        MockHttpSession admin = admin();
        String p = prefix();

        assertThat(create(admin, body(p + "a72", "영문", "APPROVER", "a".repeat(72))).getResponse().getStatus()).isEqualTo(201);
        assertThat(login(p + "a72", "a".repeat(72)).getResponse().getStatus()).isEqualTo(200);
        assertThat(create(admin, body(p + "k24", "한글", "APPROVER", "가".repeat(24))).getResponse().getStatus()).isEqualTo(201);

        for (String pw : List.of("a".repeat(73), "가".repeat(25), "a".repeat(128), "a".repeat(129), "1234567")) {
            MvcResult r = create(admin, body(p + "bad" + pw.length(), "길이", "APPROVER", pw));
            assertInvalid(r, "initial_password");
        }
        assertThat(count("select count(*) from ops.users where login_id like ?", p + "bad%")).isZero();
    }

    @Test
    void 실패_공백만인_비밀번호는_반각이든_전각이든_400이고_계정도_기록도_남지_않는다() throws Exception {
        MockHttpSession admin = admin();
        String p = prefix();
        long logsBefore = count("select count(*) from ops.access_logs where action = 'USER_CREATED'");

        assertInvalid(create(admin, body(p + "sp", "공백", "APPROVER", " ".repeat(8))), "initial_password");
        assertInvalid(create(admin, body(p + "ide", "전각", "APPROVER", "\u3000".repeat(8))), "initial_password");

        assertThat(count("select count(*) from ops.users where login_id like ?", p + "%")).isZero();
        assertThat(count("select count(*) from ops.access_logs where action = 'USER_CREATED'")).isEqualTo(logsBefore);
    }

    @Test
    void 경계_앞뒤_공백이_섞인_비밀번호는_자르지_않고_그대로_발급되며_그_값으로_로그인된다() throws Exception {
        MockHttpSession admin = admin();
        String loginId = prefix() + "pad";

        assertThat(create(admin, body(loginId, "공백 섞임", "APPROVER", " abcdefgh ")).getResponse().getStatus()).isEqualTo(201);

        assertThat(login(loginId, " abcdefgh ").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(loginId, "abcdefgh").getResponse().getStatus()).isEqualTo(401);   // 잘라서 저장했다면 200
    }

    @Test
    void 실패_형식이_틀리면_400이고_details_키는_그_칸() throws Exception {
        MockHttpSession admin = admin();
        String p = prefix();
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("login_id", body("ab", "이름", "APPROVER", PW));
        cases.put("login_id ", body(p + "a b", "이름", "APPROVER", PW));
        cases.put("login_id  ", body(p + "x".repeat(64), "이름", "APPROVER", PW));
        cases.put("display_name", body(p + "blank", "   ", "APPROVER", PW));
        cases.put("display_name ", body(p + "long", "가".repeat(51), "APPROVER", PW));
        cases.put("role", body(p + "sys", "이름", "SYSTEM", PW));
        cases.put("role ", body(p + "foo", "이름", "FOO", PW));
        cases.put("initial_password", body(p + "nopw", "이름", "APPROVER", null));
        for (Map.Entry<String, Map<String, Object>> c : cases.entrySet()) {
            assertInvalid(create(admin, c.getValue()), c.getKey().strip());
        }
    }

    @Test
    void 실패_있는_아이디는_409_LOGIN_ID_TAKEN이고_행이_늘지_않는다() throws Exception {
        MockHttpSession admin = admin();
        String loginId = prefix() + "dup";
        assertThat(create(admin, body(loginId, "처음", "APPROVER", PW)).getResponse().getStatus()).isEqualTo(201);

        MvcResult r = create(admin, body(loginId, "두 번째", "INVESTIGATOR", PW));

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("LOGIN_ID_TAKEN");
        assertThat(count("select count(*) from ops.users where login_id = ?", loginId)).isEqualTo(1);
    }

    /**
     * 동시 발급 재현 — 다른 트랜잭션이 같은 아이디를 넣고 아직 커밋하지 않았을 때 발급을 보내면 exists 검사는 지나고
     * INSERT 가 unique 잠금에서 기다린다. 그 트랜잭션이 커밋되면 unique 위반 → 500 이 아니라 409 LOGIN_ID_TAKEN.
     */
    @Test
    void 실패_동시에_같은_아이디로_DB_unique에_걸려도_409_LOGIN_ID_TAKEN() throws Exception {
        MockHttpSession admin = admin();
        String loginId = prefix() + "race";
        AtomicReference<MvcResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                result.set(create(admin, body(loginId, "두 번째", "APPROVER", PW)));
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbc.update("""
                    insert into ops.users (login_id, display_name, role, password_hash, is_active, created_by, updated_by)
                    values (?, '먼저', 'APPROVER', null, true, 1, 1)
                    """, loginId);
            second.start();
            waitUntilInsertIsBlocked();
        });   // 여기서 커밋 → 기다리던 INSERT 가 unique 위반
        second.join(10_000);

        assertThat(failure.get()).isNull();
        assertThat(result.get().getResponse().getStatus()).as(result.get().getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(result.get())).isEqualTo("LOGIN_ID_TAKEN");
        assertThat(count("select count(*) from ops.users where login_id = ?", loginId)).isEqualTo(1);
    }

    private void waitUntilInsertIsBlocked() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            jdbc.queryForObject("select pg_stat_clear_snapshot()", Object.class);   // 트랜잭션 안에서는 첫 스냅샷이 고정된다
            Long blocked = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and query ilike 'insert into %users%'
                    """, Long.class);
            if (blocked != null && blocked > 0) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("발급 INSERT 가 잠금에서 기다리지 않았어요");
    }

    // --- 재활성화 -----------------------------------------------------------------

    @Test
    void 정상_재활성화는_200이고_다시_로그인되며_기록은_없다() throws Exception {
        MockHttpSession admin = admin();
        Issued apr = issue(admin, "APPROVER");
        jdbc.update("update ops.users set is_active = false where id = ?", apr.id);   // 비활성화 API 는 다음 PR
        assertThat(login(apr.loginId, PW).getResponse().getStatus()).isEqualTo(401);

        MvcResult r = command(admin, apr.id, "activate", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).path("id").asLong()).isEqualTo(apr.id);
        assertThat(json(r).path("is_active").asBoolean()).isTrue();
        assertThat(activeOf(apr.id)).isTrue();
        assertThat(login(apr.loginId, PW).getResponse().getStatus()).isEqualTo(200);
        assertThat(logs(apr.id)).containsExactly(List.of("USER_CREATED", adminId()));   // 재활성화는 기록 없음
    }

    @Test
    void 경계_이미_활성을_활성화하면_200_지금_한_줄() throws Exception {
        MockHttpSession admin = admin();
        Issued inv = issue(admin, "INVESTIGATOR");
        activeCase(inv.id);

        MvcResult r = command(admin, inv.id, "activate", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).path("is_active").asBoolean()).isTrue();
        assertThat(json(r).path("active_case_count").asLong()).isEqualTo(1);
        assertThat(logs(inv.id)).containsExactly(List.of("USER_CREATED", adminId()));
    }

    @Test
    void 경계_다른_관리자는_막지_않는다() throws Exception {
        MockHttpSession admin = admin();
        Issued other = issue(admin, "ADMIN");
        jdbc.update("update ops.users set is_active = false where id = ?", other.id);

        MvcResult r = command(admin, other.id, "activate", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).get("active_case_count").isNull()).isTrue();
    }

    // --- 막는 대상 · 없는 대상 ---------------------------------------------------------

    @Test
    void 실패_관리자_본인과_SYSTEM은_409_ACCOUNT_CHANGE_NOT_ALLOWED와_reason() throws Exception {
        MockHttpSession admin = admin();
        long me = adminId();
        for (Map.Entry<Long, String> target : Map.of(me, "SELF", SYSTEM_ID, "SYSTEM").entrySet()) {
            MvcResult r = command(admin, target.getKey(), "activate", null);
            assertThat(r.getResponse().getStatus()).as(target.getValue()).isEqualTo(409);
            assertThat(code(r)).as(target.getValue()).isEqualTo("ACCOUNT_CHANGE_NOT_ALLOWED");
            assertThat(json(r).path("error").path("details").path("reason").asText()).isEqualTo(target.getValue());
        }
        assertThat(activeOf(SYSTEM_ID)).isFalse();   // SYSTEM 은 비활성 그대로
        assertThat(status(admin, ME)).isEqualTo(200);
    }

    @Test
    void 실패_없는_id는_404_USER_NOT_FOUND() throws Exception {
        MvcResult r = command(admin(), 987_654_321L, "activate", null);

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(r)).isEqualTo("USER_NOT_FOUND");
    }

    // --- 권한 · CSRF ------------------------------------------------------------

    @Test
    void 실패_조사역과_승인자는_403_비로그인은_401_CSRF_없으면_403() throws Exception {
        MockHttpSession admin = admin();
        Issued apr = issue(admin, "APPROVER");
        jdbc.update("update ops.users set is_active = false where id = ?", apr.id);
        for (String loginId : List.of("inv01", "apr01")) {
            MockHttpSession s = session(login(loginId, SEED_PASSWORD));
            assertThat(create(s, body(prefix() + "x", "이름", "APPROVER", PW)).getResponse().getStatus()).as(loginId).isEqualTo(403);
            assertThat(command(s, apr.id, "activate", null).getResponse().getStatus()).as(loginId).isEqualTo(403);
        }

        MvcResult anonymous = mvc.perform(post(URL + "/" + apr.id + "/activate").with(csrf())).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);

        MvcResult noCsrf = mvc.perform(post(URL + "/" + apr.id + "/activate").session(admin)).andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(activeOf(apr.id)).isFalse();
    }

    // --- 비밀번호 노출 ------------------------------------------------------------

    @Test
    void 보안_발급의_응답과_로그_어디에도_비밀번호가_없다(CapturedOutput output) throws Exception {
        MockHttpSession admin = admin();
        String first = ("secret-first-" + UUID.randomUUID()).substring(0, 40);
        String tooLong = ("secret-long-" + UUID.randomUUID()).substring(0, 40) + "x".repeat(40);
        String loginId = prefix() + "sec";

        MvcResult created = create(admin, body(loginId, "비밀", "INVESTIGATOR", first));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);   // 빈 응답으로 통과하지 않게
        MvcResult dup = create(admin, body(loginId, "비밀", "INVESTIGATOR", first));
        MvcResult invalid = create(admin, body(prefix() + "sec2", "비밀", "INVESTIGATOR", tooLong));
        assertThat(List.of(dup, invalid)).extracting(x -> x.getResponse().getStatus()).containsExactly(409, 400);

        assertThat(created.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase()).doesNotContain("password");
        for (MvcResult r : List.of(created, dup, invalid)) {   // 오류의 details 키(initial_password)는 되지만 값은 안 된다
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain(first).doesNotContain(tooLong).doesNotContain("$2a$");
        }
        assertThat(output.getAll()).doesNotContain(first).doesNotContain(tooLong);
    }

    // --- 목록 active_case_count -----------------------------------------------------

    @Test
    void 경계_목록은_조사역_행만_진행_중_담당_건수이고_0도_숫자_다른_역할은_null() throws Exception {
        MockHttpSession admin = admin();
        Issued busy = issue(admin, "INVESTIGATOR");
        Issued idle = issue(admin, "INVESTIGATOR");
        issue(admin, "APPROVER");
        issue(admin, "ADMIN");
        activeCase(busy.id);
        activeCase(busy.id);

        JsonNode body = json(mvc.perform(get(URL + "?size=100").session(admin)).andReturn());

        int seen = 0;
        for (JsonNode item : body.path("items")) {
            JsonNode count = item.get("active_case_count");
            if ("INVESTIGATOR".equals(item.path("role").asText())) {
                assertThat(count.isNumber()).as(item.toString()).isTrue();
            } else {
                assertThat(count.isNull()).as(item.toString()).isTrue();
            }
            long id = item.path("id").asLong();
            if (id == busy.id) { assertThat(count.asLong()).isEqualTo(2); seen++; }
            if (id == idle.id) { assertThat(count.asLong()).isZero(); seen++; }
        }
        assertThat(seen).isEqualTo(2);
    }

    // --- 도우미 -------------------------------------------------------------------

    record Issued(long id, String loginId) {}

    private Issued issue(MockHttpSession admin, String role) throws Exception {
        String loginId = prefix() + role.toLowerCase().charAt(0);
        MvcResult r = create(admin, body(loginId, "발급 " + role, role, PW));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return new Issued(json(r).path("id").asLong(), loginId);
    }

    private String uniqueThree() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        long n = SEQ.incrementAndGet();
        return "" + chars.charAt((int) (n % 36)) + chars.charAt((int) (n / 36 % 36)) + chars.charAt((int) (n / 1296 % 36));
    }

    private static Map<String, Object> body(String loginId, String displayName, String role, String password) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("login_id", loginId);
        m.put("display_name", displayName);
        m.put("role", role);
        if (password != null) m.put("initial_password", password);
        return m;
    }

    private MvcResult create(MockHttpSession s, Map<String, Object> body) throws Exception {
        return mvc.perform(post(URL).session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body))).andReturn();
    }

    private MvcResult command(MockHttpSession s, long id, String verb, Map<String, Object> body) throws Exception {
        var req = post(URL + "/" + id + "/" + verb).session(s).with(csrf());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        return mvc.perform(req).andReturn();
    }

    private int status(MockHttpSession s, String url) throws Exception {
        return mvc.perform(get(url).session(s)).andReturn().getResponse().getStatus();
    }

    private void assertInvalid(MvcResult r, String field) throws Exception {
        String what = field + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(what).isEqualTo(400);
        assertThat(code(r)).as(what).isEqualTo("INVALID_REQUEST");
        assertThat(keys(json(r).path("error").path("details"))).as(what).contains(field);
    }

    /** 대상 사용자의 access_logs 행 [action, user_id] — 오래된 것부터. */
    private List<List<Object>> logs(long targetUserId) {
        return jdbc.query("""
                select action, user_id from ops.access_logs
                where target_type = 'USER' and target_id = ? order by id
                """, (rs, i) -> List.<Object>of(rs.getString(1), rs.getLong(2)), targetUserId);
    }

    private long adminId() {
        return jdbc.queryForObject("select id from ops.users where login_id = 'admin'", Long.class);
    }

    private boolean activeOf(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select is_active from ops.users where id = ?", Boolean.class, id));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** REVIEWING 케이스 하나를 그 사람 담당으로 — 진행 중 담당 1건. */
    private void activeCase(long assigneeId) {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-01-02', 'RUNNING', 1) returning id
                """, Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id
                """, Long.class, "R_UC_" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-01-02 00:00:00', '2023-01-02 23:59:59', '2023-01-02 23:59:59',
                        '{}'::jsonb, '2023-01-02')
                returning id
                """, Long.class, "ALT-20230103-%06d".formatted(n), batchId, ruleId, "011_U" + n);
        jdbc.update("""
                insert into ops.cases (case_no, alert_id, account_key, assignee_id, status, created_by, updated_by)
                values (?, ?, ?, ?, 'REVIEWING', 1, 1)
                """, "CASE-2024-%06d".formatted(n), alertId, "011_U" + n, assigneeId);
    }

    private static String prefix() {
        return "t1352-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    private MockHttpSession admin() throws Exception {
        return session(login("admin", SEED_PASSWORD));
    }

    private MvcResult login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", password)))).andReturn();
    }

    private static MockHttpSession session(MvcResult r) {
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String code(MvcResult r) throws Exception {
        return json(r).path("error").path("code").asText();
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
