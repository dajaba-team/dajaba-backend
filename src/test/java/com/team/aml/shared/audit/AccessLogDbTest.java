package com.team.aml.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.security.AppUserDetails;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * access_logs (V004_3, SCRUM-19 B) — 실제 PostgreSQL + 필터 체인 전체.
 * 테스트마다 다른 IP 로 요청해서 같은 컨테이너 안의 행이 서로 섞이지 않게 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + AccessLogDbTest.PASSWORD})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessLogDbTest {

    static final String PASSWORD = "test-access-log-pass-1234";

    @Container
    @ServiceConnection(type = JdbcConnectionDetails.class)
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    // Flyway 는 실제 DDL 소유자로 실행해야 revoke 를 검증할 수 있다 (AiCallLogDbTest 와 같음).
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired AccessLogWriter accessLog;
    @SpyBean AccessLogRepository repository;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_로그인_성공은_LOGIN_OK_한_행이고_시도한_아이디는_비운다() throws Exception {
        MvcResult r = login("inv01", PASSWORD, "10.1.0.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> row = onlyRow("10.1.0.1");
        assertThat(row.get("action")).isEqualTo("LOGIN_OK");
        assertThat(row.get("user_id")).isEqualTo(userId("inv01"));
        assertThat(row.get("login_id_attempted")).isNull();
        assertThat(row.get("target_type")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void 정상_로그아웃은_그_사용자의_LOGOUT_한_행() throws Exception {
        MvcResult r = login("apr01", PASSWORD, "10.1.0.2");
        Cookie xsrf = xsrf(r);
        MvcResult out = mvc.perform(post("/api/v1/auth/logout").session((MockHttpSession) r.getRequest().getSession(false))
                .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .with(req -> { req.setRemoteAddr("10.1.0.2"); return req; })).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(204);

        assertThat(jdbc.queryForList("select action from ops.access_logs where host(ip) = '10.1.0.2' order by id", String.class))
                .containsExactly("LOGIN_OK", "LOGOUT");
        assertThat(jdbc.queryForObject("select user_id from ops.access_logs where host(ip) = '10.1.0.2' and action = 'LOGOUT'",
                Long.class)).isEqualTo(userId("apr01"));
    }

    @Test
    void 정상_조회_트랜잭션_안에서_view는_로그인_사용자로_한_행() {
        long me = signIn("inv01", "INVESTIGATOR", "10.1.0.3");
        inTx(() -> accessLog.view(TargetType.CASE, 7L));

        Map<String, Object> row = onlyRow("10.1.0.3");
        assertThat(row.get("action")).isEqualTo("VIEW");
        assertThat(row.get("target_type")).isEqualTo("CASE");
        assertThat(row.get("target_id")).isEqualTo(7L);
        assertThat(row.get("user_id")).isEqualTo(me);
    }

    @Test
    void 정상_view는_바깥_조회와_같은_커넥션_하나만_쓴다() throws SQLException {   // REQUIRES_NEW 면 저장 순간 2 (SCRUM-93)
        signIn("inv01", "INVESTIGATOR", "10.1.0.12");
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        AtomicInteger activeAtSave = new AtomicInteger(-1);
        // 저장소는 인터페이스라 callRealMethod 가 안 됨 → @SpyBean 이 진짜 저장소로 넘기는 기본 응답을 그대로 씀
        Answer<?> realSave = Mockito.mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(inv -> {
            activeAtSave.set(pool.getActiveConnections());
            return realSave.answer(inv);
        }).when(repository).save(any());

        inTx(() -> {
            jdbc.queryForObject("select 1", Integer.class);   // 조회 유스케이스가 먼저 커넥션을 쥔 상태
            accessLog.view(TargetType.CASE, 7L);
        });

        assertThat(activeAtSave.get()).isEqualTo(1);
        assertThat(count("10.1.0.12")).isEqualTo(1L);
    }

    @Test
    void 정상_strView와_pdfDownload는_STR_DRAFT_대상이다() {
        signIn("apr01", "APPROVER", "10.1.0.4");
        inTx(() -> {
            accessLog.strView(31L);
            accessLog.pdfDownload(31L);
        });
        assertThat(jdbc.queryForList("""
                select action || ':' || target_type || ':' || target_id from ops.access_logs
                where host(ip) = '10.1.0.4' order by id
                """, String.class)).containsExactly("STR_VIEW:STR_DRAFT:31", "PDF_DOWNLOAD:STR_DRAFT:31");
    }

    @Test
    void 정상_export는_ADMIN_내보내기_대상과_EXPORT_action() {
        long admin = signIn("admin", "ADMIN", "10.1.0.11");
        inTx(() -> accessLog.export(TargetType.CASE, 5L));

        Map<String, Object> row = onlyRow("10.1.0.11");
        assertThat(row.get("action")).isEqualTo("EXPORT");
        assertThat(row.get("target_type")).isEqualTo("CASE");
        assertThat(row.get("target_id")).isEqualTo(5L);
        assertThat(row.get("user_id")).isEqualTo(admin);
    }

    @Test
    void 정상_AccessLogAction_10개와_TargetType_4개는_DB_CHECK가_모두_받는다() throws SQLException {   // enum·CHECK 어긋남 방지
        assertThat(AccessLogAction.values()).hasSize(10);
        assertThat(TargetType.values()).hasSize(4);
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
             var action = connection.prepareStatement("insert into ops.access_logs (action, ip) values (?, '10.1.0.10')");
             var target = connection.prepareStatement(
                     "insert into ops.access_logs (action, target_type, target_id, ip) values ('VIEW', ?, 1, '10.1.0.10')")) {
            for (AccessLogAction a : AccessLogAction.values()) {
                action.setString(1, a.name());
                assertThat(action.executeUpdate()).as(a.name()).isEqualTo(1);
            }
            for (TargetType t : TargetType.values()) {
                target.setString(1, t.name());
                assertThat(target.executeUpdate()).as(t.name()).isEqualTo(1);
            }
        }
        assertThat(count("10.1.0.10")).isEqualTo(14L);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_65자_login_id는_400이고_기록하지_않는다() throws Exception {   // LOGIN_FAIL 은 인증까지 간 실패만 (11/2)
        MvcResult r = login("a".repeat(65), PASSWORD, "10.1.0.5");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("10.1.0.5")).isZero();
    }

    @Test
    void 경계_기록_뒤_조회가_롤백되면_기록도_남지_않는다() {   // 같이 성공·같이 실패 (SCRUM-93)
        signIn("inv01", "INVESTIGATOR", "10.1.0.13");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accessLog.view(TargetType.CASE, 7L);
            status.setRollbackOnly();   // 기록 뒤 본문 조회가 실패한 것과 같음
        });
        assertThat(count("10.1.0.13")).isZero();
    }

    @Test
    void 경계_Flyway는_V004_1_V004_2_다음에_V004_3을_적용하고_FK는_users_하나() {
        assertThat(Arrays.stream(flyway.info().applied()).map(m -> m.getVersion()).toList())
                .containsSubsequence(
                        MigrationVersion.fromVersion("4"),
                        MigrationVersion.fromVersion("4.1"),
                        MigrationVersion.fromVersion("4.2"),
                        MigrationVersion.fromVersion("4.3"));
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where table_schema = 'ops' and table_name = 'access_logs' and constraint_type = 'FOREIGN KEY'
                """, Long.class)).isEqualTo(1L);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_틀린비밀번호와_없는ID는_LOGIN_FAIL이고_비밀번호는_어디에도_없다() throws Exception {
        assertThat(login("inv01", "wrong-pass-zz9", "10.1.0.6").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("nobody-zz9", "wrong-pass-zz9", "10.1.0.6").getResponse().getStatus()).isEqualTo(401);

        // 있는 아이디(inv01)여도 user_id 는 null, login_id_attempted 만
        assertThat(jdbc.queryForList("""
                select action || ':' || coalesce(user_id::text, 'null') || ':' || login_id_attempted
                from ops.access_logs where host(ip) = '10.1.0.6' order by id
                """, String.class)).containsExactly("LOGIN_FAIL:null:inv01", "LOGIN_FAIL:null:nobody-zz9");
        assertThat(jdbc.queryForObject("select count(*) from ops.access_logs a where row_to_json(a)::text like '%wrong-pass-zz9%'",
                Long.class)).isZero();
    }

    @Test
    void 실패_view_저장이_실패하면_예외를_그대로_올린다() {   // 기록 없는 열람 금지 (11/2)
        signIn("inv01", "INVESTIGATOR", "10.1.0.7");
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());
        assertThatThrownBy(() -> inTx(() -> accessLog.view(TargetType.CASE, 8L))).isInstanceOf(DataAccessException.class);
        assertThat(count("10.1.0.7")).isZero();
    }

    @Test
    void 실패_readOnly_트랜잭션_안에서_view는_바로_실패하고_남지_않는다() {   // 03_shared §9 자주 하는 실수 ①
        signIn("inv01", "INVESTIGATOR", "10.1.0.14");
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> accessLog.view(TargetType.CASE, 7L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThat(count("10.1.0.14")).isZero();
    }

    @Test
    void 실패_트랜잭션_밖에서_view는_바로_실패하고_남지_않는다() {   // 03_shared §9 자주 하는 실수 ② (컨트롤러 등)
        signIn("inv01", "INVESTIGATOR", "10.1.0.15");
        assertThatThrownBy(() -> accessLog.view(TargetType.CASE, 7L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("10.1.0.15")).isZero();
    }

    @Test
    void 실패_로그인_사용자가_없으면_view는_실패하고_남지_않는다() {   // user_id null 열람 행 금지
        signIn("inv01", "INVESTIGATOR", "10.1.0.16");
        SecurityContextHolder.clearContext();   // 요청(IP)은 있고 사용자만 없음
        assertThatThrownBy(() -> inTx(() -> accessLog.view(TargetType.CASE, 7L)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("10.1.0.16")).isZero();
    }

    @Test
    void 실패_로그인_기록이_실패해도_로그인은_200() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());
        assertThat(login("inv02", PASSWORD, "10.1.0.8").getResponse().getStatus()).isEqualTo(200);
        assertThat(count("10.1.0.8")).isZero();
    }

    @Test
    void 실패_app_backend는_추가만_되고_수정과_삭제는_거부된다() throws SQLException {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
             var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(
                    "insert into ops.access_logs (action, ip) values ('LOGOUT', '10.1.0.9')")).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("update ops.access_logs set action = 'VIEW'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("delete from ops.access_logs"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void 실패_정해진_action과_target_type_밖의_값은_DB가_거부한다() throws SQLException {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("insert into ops.access_logs (action) values ('ACCESS_DENIED')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("check");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "insert into ops.access_logs (action, target_type, target_id) values ('VIEW', 'BATCH_RUN', 1)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("check");
        }
    }

    // --- 도우미 -------------------------------------------------------------

    private MvcResult login(String loginId, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("login_id", loginId, "password", password)))
                .with(req -> { req.setRemoteAddr(ip); return req; })).andReturn();
    }

    /** 조회 유스케이스 안과 같은 상태 — 로그인한 사용자 + 현재 요청(IP). */
    private long signIn(String loginId, String role, String ip) {
        long id = userId(loginId);
        AppUserDetails me = new AppUserDetails(id, loginId, loginId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return id;
    }

    /** 열람 기록을 부르는 조회 유스케이스와 같은 트랜잭션 — @Transactional (readOnly 아님). */
    private void inTx(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> body.run());
    }

    private long userId(String loginId) {
        return jdbc.queryForObject("select id from ops.users where login_id = ?", Long.class, loginId);
    }

    private Map<String, Object> onlyRow(String ip) {
        return jdbc.queryForMap("select * from ops.access_logs where host(ip) = ?", ip);
    }

    private long count(String ip) {
        return jdbc.queryForObject("select count(*) from ops.access_logs where host(ip) = ?", Long.class, ip);
    }

    /** 응답의 마지막 XSRF-TOKEN 쿠키 (LoginIntegrationTest 와 같음). */
    private static Cookie xsrf(MvcResult r) {
        Cookie last = null;
        for (Cookie c : r.getResponse().getCookies()) {
            if ("XSRF-TOKEN".equals(c.getName())) last = c;
        }
        assertThat(last).as("XSRF-TOKEN 쿠키").isNotNull();
        return new Cookie("XSRF-TOKEN", last.getValue());
    }
}
