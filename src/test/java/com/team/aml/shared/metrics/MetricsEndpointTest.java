package com.team.aml.shared.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표 끝점 (03_shared §13, SCRUM-271) — 실제 포트 두 개(앱·관리) + 실제 PostgreSQL + 필터 체인 전체.
 * RANDOM_PORT 는 management.server.port 가 앱 포트와 다르면 관리 포트도 랜덤으로 바꾼다.
 * application.yml 에서 8081 을 지우면 두 포트가 같아져 "앱 포트에서는 막힘" 테스트가 깨진다.
 * Boot 테스트는 지표 내보내기를 기본으로 끄므로 @AutoConfigureObservability 로 켠다(추적은 안 씀).
 */
@AutoConfigureObservability(tracing = false)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aml.dev-users.enabled=true", "aml.dev-users.password=" + MetricsEndpointTest.PASSWORD})
@ActiveProfiles("test")
class MetricsEndpointTest {

    static final String PASSWORD = "test-metrics-pass-1234";

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

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    // 계좌·금액·화면 번호·login_id·request_id — 요청에 넣고 scrape 결과에 안 나오는지 본다
    static final String ACCOUNT = "10_8000EBD30";
    static final String AMOUNT = "15000000";
    static final String CASE_NO = "CASE-2022-000057";
    static final String DRAFT_NO = "DRF-000123";
    static final String ALERT_NO = "ALT-2022-000999";
    static final String LOGIN_ID = "inv01";
    static final String REQUEST_ID = "reqmetricsprobe42";

    record Res(int status, String body) {}

    /** 쿠키(세션·XSRF-TOKEN)를 들고 다니는 브라우저 하나. */
    static final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Res send(HttpRequest.Builder req) throws IOException, InterruptedException {
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body());
        }

        String xsrf() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElse(null);
        }
    }

    private URI app(String path) {
        return URI.create("http://localhost:" + appPort + path);
    }

    private URI mgmt(String path) {
        return URI.create("http://localhost:" + managementPort + path);
    }

    private Res get(Browser b, URI uri) throws Exception {
        return b.send(HttpRequest.newBuilder(uri).GET().header("X-Request-Id", REQUEST_ID));
    }

    private Browser loggedIn() throws Exception {
        Browser b = new Browser();
        Res login = b.send(HttpRequest.newBuilder(app("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"login_id\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(login.status()).isEqualTo(200);
        return b;
    }

    private String scrape() throws Exception {
        Res res = get(new Browser(), mgmt("/actuator/prometheus"));
        assertThat(res.status()).isEqualTo(200);
        return res.body();
    }

    /** aml_http_errors_total{code="…",status="…"} 값. 없으면 0. 테스트끼리 컨텍스트를 같이 쓰니 전후 차이로 본다. */
    private double errors(String scrape, String code, String status) {
        Matcher m = Pattern.compile("^aml_http_errors_total\\{code=\"" + code + "\",status=\"" + status + "\"} (\\S+)$",
                Pattern.MULTILINE).matcher(scrape);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // --- 끝점: 정상 ---------------------------------------------------------

    @Test
    void 정상_관리_포트_prometheus는_로그인_없이_200이고_HTTP_지표가_보인다() throws Exception {
        get(new Browser(), app("/actuator/health"));   // 요청 하나는 있어야 http_server_requests 가 생긴다

        String body = scrape();

        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(body).contains("http_server_requests_seconds_count");
    }

    @Test
    void 정상_응답_시간_버킷은_지정한_10개와_Inf뿐이다() throws Exception {
        get(new Browser(), app("/actuator/health"));

        Set<Double> le = new TreeSet<>();
        Matcher m = Pattern.compile("^http_server_requests_seconds_bucket\\{.*le=\"([^\"]+)\".*} ", Pattern.MULTILINE)
                .matcher(scrape());
        while (m.find()) {
            le.add(m.group(1).equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(m.group(1)));
        }

        assertThat(le).containsExactly(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 90.0,
                Double.POSITIVE_INFINITY);
    }

    // --- 끝점: 경계 — 앱 포트 health 는 전과 같은 모양 ----------------------

    @Test
    void 경계_앱_포트_health는_전과_같이_200_status_UP만() throws Exception {
        Res res = get(new Browser(), app("/actuator/health"));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(res.body()).doesNotContain("components", "details");
    }

    // --- 끝점: 실패 — 앱 포트로는 지표가 안 나간다 ---------------------------

    @Test
    void 실패_앱_포트_prometheus는_비로그인_401_로그인_403() throws Exception {
        Res anonymous = get(new Browser(), app("/actuator/prometheus"));
        Res member = get(loggedIn(), app("/actuator/prometheus"));

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(member.status()).isEqualTo(403);
        assertThat(anonymous.body() + member.body()).doesNotContain("http_server_requests");
    }

    @Test
    void 실패_관리_포트에서도_다른_actuator_끝점은_안_열린다() throws Exception {
        Res env = get(new Browser(), mgmt("/actuator/env"));
        Res beans = get(new Browser(), mgmt("/actuator/beans"));

        assertThat(env.status()).isNotEqualTo(200);
        assertThat(beans.status()).isNotEqualTo(200);
    }

    // --- 에러 카운터: 보안 필터 응답도 센다 ---------------------------------

    @Test
    void 정상_보안_필터의_401_403_CSRF와_핸들러의_404를_code별로_센다() throws Exception {
        String before = scrape();

        get(new Browser(), app("/api/v1/cases"));                                     // 401 UNAUTHENTICATED
        Browser member = loggedIn();
        member.send(HttpRequest.newBuilder(app("/api/v1/cases/" + CASE_NO + "/notes"))   // 403 CSRF_INVALID (토큰 없음)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")));
        get(member, app("/api/v1/admin/users"));                                      // 403 FORBIDDEN (관리자 아님)
        get(member, app("/api/v1/cases/" + CASE_NO));                                 // 404 CASE_NOT_FOUND

        String after = scrape();
        assertThat(errors(after, "UNAUTHENTICATED", "401") - errors(before, "UNAUTHENTICATED", "401")).isEqualTo(1);
        assertThat(errors(after, "CSRF_INVALID", "403") - errors(before, "CSRF_INVALID", "403")).isEqualTo(1);
        assertThat(errors(after, "FORBIDDEN", "403") - errors(before, "FORBIDDEN", "403")).isEqualTo(1);
        assertThat(errors(after, "CASE_NOT_FOUND", "404") - errors(before, "CASE_NOT_FOUND", "404")).isEqualTo(1);
    }

    // --- 라벨: 값이 무한한 것·개인정보가 지표에 안 들어간다 (NFR-COM-04) -------

    @Test
    void 실패_계좌_금액_화면번호_login_id_request_id는_scrape_어디에도_없다() throws Exception {
        Browser member = loggedIn();
        String query = "?account=" + ACCOUNT + "&amount=" + AMOUNT;
        get(member, app("/api/v1/cases/" + CASE_NO + query));
        get(member, app("/api/v1/cases/" + CASE_NO + "/transactions" + query));
        get(member, app("/api/v1/str-drafts/" + DRAFT_NO + query));
        get(member, app("/api/v1/admin/alerts/" + ALERT_NO + query));
        get(member, app("/api/v1/no-such-path/" + ACCOUNT));
        String xsrf = member.xsrf();
        member.send(HttpRequest.newBuilder(app("/api/v1/cases/" + CASE_NO + "/notes"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", xsrf == null ? "" : xsrf)
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"investigator_notes\":\"" + ACCOUNT + " " + AMOUNT + "\",\"version\":1}")));

        String body = scrape();

        assertThat(body).contains("aml_http_errors_total");
        assertThat(body).doesNotContain(ACCOUNT, AMOUNT, CASE_NO, DRAFT_NO, ALERT_NO, "CASE-", "DRF-", "ALT-",
                "login_id", "\"" + LOGIN_ID + "\"", REQUEST_ID, "no-such-path");
    }
}
