package com.team.aml.cases.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.cases.application.WasCaseAssignee;
import com.team.aml.shared.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/v1/notifications — 업무 알림 (SCRUM-355, 노션 「비동기 작업 알림 설계 문서」 6.5·12절). 실제 PostgreSQL.
 * 이력은 SQL 로 바로 넣는다 — 시각을 정해야 하고, V010 전 옛 행도 만들어야 해서.
 *
 * <p>승인 요청은 "맡은 적 없는 승인자 전원"이 받아서 다른 테스트의 행도 보일 수 있다. 그래서 승인자 쪽은
 * 이 테스트의 케이스 번호로만 확인한다. 행은 t0(시작 10초 전) 뒤에 1ms 씩 찍는다 — 응답이 server_time(지금 초 버림 − 5초)
 * 까지만 읽어서, 지금 시각에 찍으면 아직 안 보인다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final String URL = "/api/v1/notifications";

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
    @Autowired JdbcTemplate jdbc;
    @Autowired WasCaseAssignee wasCaseAssignee;
    @Autowired DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();
    private AppUserDetails inv;      // 지금 담당 조사역
    private AppUserDetails inv2;     // 다른 조사역
    private AppUserDetails apr;      // 승인자 — 맡은 적 없음
    private AppUserDetails admin;
    private Instant t0;
    private int tick;

    @BeforeEach
    void users() {
        long n = SEQ.incrementAndGet();
        inv = newUser("ntf_inv" + n, "INVESTIGATOR");
        inv2 = newUser("ntf_invb" + n, "INVESTIGATOR");
        apr = newUser("ntf_apr" + n, "APPROVER");
        admin = newUser("ntf_adm" + n, "ADMIN");
        t0 = Instant.now().minusSeconds(10);
        tick = 0;
    }

    // ── 정상: 7종이 각자 받는 사람에게만 ─────────────────────────────

    @Test
    void 정상_한_케이스의_흐름이_받는_사람마다_맞는_종류로_최신순으로_나온다() throws Exception {
        TestCase c = newCase(inv.userId(), true);
        history(c.id, "ASSIGN", null, admin, null, inv2.userId());
        history(c.id, "REASSIGN", null, admin, inv2.userId(), inv.userId());
        history(c.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);
        history(c.id, "REJECT", "NEED_REVISION", apr, null, null);
        history(c.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);
        history(c.id, "APPROVE", null, apr, null, null);
        history(c.id, "REPORT", null, apr, null, null);

        JsonNode mine = list(inv, since(t0));
        assertThat(types(mine)).containsExactly("STR_REPORTED", "STR_APPROVED", "STR_REJECTED", "CASE_REASSIGNED_TO_ME");
        assertThat(mine.get("total").asInt()).isEqualTo(4);
        JsonNode rejected = mine.get("items").get(2);
        assertThat(rejected.get("case_no").asText()).isEqualTo(c.no);
        assertThat(rejected.get("draft_no").asText()).isEqualTo(c.draftNo);
        assertThat(rejected.get("reason_code").asText()).isEqualTo("NEED_REVISION");
        assertThat(rejected.get("actor").get("login_id").asText()).isEqualTo(apr.loginId());
        assertThat(rejected.get("actor").get("display_name").asText()).isEqualTo(apr.displayName());
        assertThat(rejected.get("at").asText()).endsWith("Z");
        JsonNode reassigned = mine.get("items").get(3);
        assertThat(reassigned.get("draft_no").isNull()).isTrue();      // 케이스 종류는 초안 번호 없음
        assertThat(reassigned.get("reason_code").isNull()).isTrue();

        // 넘긴 사람은 배정과 빠짐 두 줄, STR 알림은 지금 담당이 아니라 0
        assertThat(types(list(inv2, since(t0)))).containsExactly("CASE_REASSIGNED_AWAY", "CASE_ASSIGNED");

        List<JsonNode> approver = itemsOf(list(apr, since(t0)), c.no);
        assertThat(approver).extracting(i -> i.get("type").asText())
                .containsExactly("APPROVAL_REQUESTED", "APPROVAL_REQUESTED");
        assertThat(approver.get(0).get("draft_no").asText()).isEqualTo(c.draftNo);
        assertThat(approver.get(0).get("actor").get("login_id").asText()).isEqualTo(inv.loginId());
    }

    @Test
    void 정상_승인_뒤_담당이_바뀌면_새_담당이_승인_알림을_받는다() throws Exception {
        TestCase c = newCase(inv2.userId(), true);                       // 지금 담당은 inv2
        history(c.id, "APPROVE", null, apr, null, null);
        history(c.id, "REASSIGN", null, admin, inv.userId(), inv2.userId());

        assertThat(types(list(inv2, since(t0)))).containsExactly("CASE_REASSIGNED_TO_ME", "STR_APPROVED");
        assertThat(types(list(inv, since(t0)))).containsExactly("CASE_REASSIGNED_AWAY");
    }

    // ── 실패·보안 ────────────────────────────────────────────────

    @Test
    void 실패_모의_제출은_지금_담당_조사역에게만_나가고_관리자_비담당_승인자는_0() throws Exception {
        TestCase c = newCase(inv.userId(), true);
        history(c.id, "REPORT", null, apr, null, null);
        AppUserDetails apr2 = newUser("ntf_apr2_" + SEQ.incrementAndGet(), "APPROVER");

        assertThat(types(list(inv, since(t0)))).containsExactly("STR_REPORTED");
        for (AppUserDetails other : List.of(inv2, apr, apr2, admin)) {
            JsonNode r = list(other, since(t0));
            assertThat(types(r)).as(other.loginId()).doesNotContain("STR_REPORTED");
            assertThat(caseNos(r)).as(other.loginId()).doesNotContain(c.no);
        }
    }

    @Test
    void 실패_그_케이스를_지금_예전에_맡은_승인자에게는_승인_요청이_안_나가고_WasCaseAssignee_보다_넓게_막는다() throws Exception {
        AppUserDetails wasAssignee = newUser("ntf_aprw" + SEQ.incrementAndGet(), "APPROVER");   // 조사역이었다가 승인자로
        AppUserDetails nowAssignee = newUser("ntf_aprn" + SEQ.incrementAndGet(), "APPROVER");
        TestCase before = newCase(inv.userId(), true);
        history(before.id, "ASSIGN", null, admin, null, wasAssignee.userId());
        history(before.id, "REASSIGN", null, admin, wasAssignee.userId(), inv.userId());
        history(before.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);
        TestCase now = newCase(nowAssignee.userId(), true);
        history(now.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);
        // V010 전에 배정받아 받은 사람 칸이 비고, 그 뒤 넘겨서 넘긴 사람(from)에만 남은 사람 (추가 검토 1)
        AppUserDetails fromOnly = newUser("ntf_aprf" + SEQ.incrementAndGet(), "APPROVER");
        TestCase gap = newCase(inv.userId(), true);
        history(gap.id, "REASSIGN", null, admin, fromOnly.userId(), inv.userId());
        history(gap.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);

        // 자기가 맡은(맡았던) 케이스만 빠지고, 맡은 적 없는 다른 케이스는 받는다
        assertThat(caseNos(list(wasAssignee, since(t0)))).doesNotContain(before.no).contains(now.no);
        assertThat(caseNos(list(nowAssignee, since(t0)))).doesNotContain(now.no).contains(before.no);
        // 승인 권한 판단과 같은 기준 — 한쪽만 바뀌면 여기서 깨진다
        assertThat(wasCaseAssignee.execute(before.id, wasAssignee.userId())).isTrue();
        assertThat(wasCaseAssignee.execute(now.id, nowAssignee.userId())).isTrue();
        assertThat(wasCaseAssignee.execute(before.id, apr.userId())).isFalse();
        assertThat(caseNos(list(apr, since(t0)))).contains(before.no, now.no, gap.no);

        // 알림은 from 에만 남은 사람도 막는다. 승인 권한 판단(WasCaseAssignee)은 to 만 봐서 아직 false — 알림 쪽이 더 넓다.
        // WasCaseAssignee 가 from 까지 보게 바뀌면 아래 false 를 true 로 바꾼다(알림 쪽은 그대로).
        assertThat(caseNos(list(fromOnly, since(t0)))).doesNotContain(gap.no).contains(before.no, now.no);
        assertThat(wasCaseAssignee.execute(gap.id, fromOnly.userId())).isFalse();
    }

    @Test
    void 실패_자기가_한_일은_안_나온다() throws Exception {
        TestCase c = newCase(inv.userId(), true);
        history(c.id, "ASSIGN", null, inv, null, inv.userId());          // 옛 자기 배정
        history(c.id, "REJECT", "NOT_REPORTABLE", inv, null, null);     // 행위자가 나 (정상 흐름엔 없음)
        history(c.id, "SUBMIT_FOR_APPROVAL", null, apr, null, null);    // 승인자 본인이 올린 행

        assertThat(caseNos(list(inv, since(t0)))).doesNotContain(c.no);
        assertThat(caseNos(list(apr, since(t0)))).doesNotContain(c.no);
    }

    @Test
    void 실패_관리자는_늘_빈_목록() throws Exception {
        TestCase c = newCase(admin.userId(), true);                      // 관리자가 담당·받은 사람인 이상한 행도
        history(c.id, "ASSIGN", null, inv, null, admin.userId());
        history(c.id, "REPORT", null, apr, null, null);
        history(c.id, "SUBMIT_FOR_APPROVAL", null, inv, null, null);

        JsonNode r = list(admin, null);
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("total").asInt()).isZero();
        assertThat(r.get("server_time").asText()).endsWith("Z");
    }

    @Test
    void 실패_반려_응답에_코멘트_메모_계좌_금액_숫자_id_가_없다() throws Exception {
        TestCase c = newCase(inv.userId(), true);
        history(c.id, "NOTE", null, inv, null, null);
        history(c.id, "REJECT", "NEED_REVISION", apr, null, null);

        String raw = raw(get(URL).param("since", since(t0)).with(user(inv)), 200);
        JsonNode r = json.readTree(raw);
        assertThat(fieldNames(r)).containsExactly("items", "total", "server_time");
        JsonNode item = r.get("items").get(0);
        assertThat(fieldNames(item)).containsExactly("type", "case_no", "draft_no", "reason_code", "actor", "at");
        assertThat(fieldNames(item.get("actor"))).containsExactly("login_id", "display_name");
        assertThat(r.get("items")).hasSize(1);                          // NOTE 는 알림 아님
        assertThat(raw).doesNotContain("반려 코멘트 본문").doesNotContain("메모 본문")
                .doesNotContain(c.accountKey).doesNotContain("comment").doesNotContain("amount")
                .doesNotContain("\"id\"").doesNotContain("draft_id").doesNotContain("case_id");
    }

    @Test
    void 실패_로그인_안_하면_401() throws Exception {
        raw(get(URL), 401);
    }

    @Test
    void 실패_since_형식_오류와_size_범위_밖은_400_INVALID_REQUEST() throws Exception {
        for (String bad : List.of("abc", "2026-11-24", "2026-11-24T11:10:00+09:00", "2026-11-24T02:10:00z",
                "2026-13-40T00:00:00Z", "Tue, 24 Nov 2026 02:10:00 GMT", "")) {
            JsonNode e = json.readTree(raw(get(URL).param("since", bad).with(user(inv)), 400));
            assertThat(e.get("error").get("code").asText()).as(bad).isEqualTo("INVALID_REQUEST");
            assertThat(e.get("error").get("details").has("since")).as(bad).isTrue();
        }
        for (String bad : List.of("0", "51", "-1", "x")) {
            JsonNode e = json.readTree(raw(get(URL).param("size", bad).with(user(inv)), 400));
            assertThat(e.get("error").get("code").asText()).as(bad).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void 실패_남의_id_를_붙여도_내_알림만() throws Exception {
        TestCase c = newCase(inv2.userId(), true);
        history(c.id, "APPROVE", null, apr, null, null);

        JsonNode r = json.readTree(raw(get(URL).param("since", since(t0)).param("user_id", inv2.userId().toString())
                .param("login_id", inv2.loginId()).with(user(inv)), 200));
        assertThat(caseNos(r)).doesNotContain(c.no);
    }

    // ── 경계 ─────────────────────────────────────────────────

    @Test
    void 경계_since_없으면_7일_30일보다_오래되면_30일로_맞춘다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        Instant now = Instant.now();
        historyAt(c.id, "ASSIGN", inv.userId(), now.minus(Duration.ofDays(6)));
        historyAt(c.id, "ASSIGN", inv.userId(), now.minus(Duration.ofDays(8)));
        historyAt(c.id, "ASSIGN", inv.userId(), now.minus(Duration.ofDays(29)));
        historyAt(c.id, "ASSIGN", inv.userId(), now.minus(Duration.ofDays(31)));

        assertThat(list(inv, null).get("total").asInt()).isEqualTo(1);                                   // 6일
        assertThat(list(inv, since(now.minus(Duration.ofDays(40)))).get("total").asInt()).isEqualTo(3);  // 31일은 빠짐
        assertThat(list(inv, since(now.minus(Duration.ofDays(10)))).get("total").asInt()).isEqualTo(2);
        assertThat(list(inv, since(now.plus(Duration.ofDays(1)))).get("items")).isEmpty();               // 미래
    }

    @Test
    void 경계_size_기본_20_최대_50_total_은_size_와_따로_99_에서_멈춘다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        for (int i = 0; i < 120; i++) {
            history(c.id, "ASSIGN", null, admin, null, inv.userId());
        }

        JsonNode def = list(inv, since(t0));
        assertThat(def.get("items")).hasSize(20);
        assertThat(def.get("total").asInt()).isEqualTo(99);
        assertThat(json.readTree(raw(get(URL).param("since", since(t0)).param("size", "50").with(user(inv)), 200))
                .get("items")).hasSize(50);
        JsonNode one = json.readTree(raw(get(URL).param("since", since(t0)).param("size", "1").with(user(inv)), 200));
        assertThat(one.get("items")).hasSize(1);
        assertThat(one.get("items").get(0).get("at").asText())
                .isEqualTo(def.get("items").get(0).get("at").asText());   // 최신 한 줄
    }

    @Test
    void 경계_V010_전_옛_배정_이력과_OPEN_수십_건이_섞여도_오류_없이_알림만() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        oldAssigneeRows(c.id);                                            // 담당자 칸 빈 ASSIGN·REASSIGN
        for (int i = 0; i < 40; i++) {
            history(c.id, "OPEN", null, null, null, null);
        }
        for (String action : List.of("NOTE", "ESCALATE", "CLOSE", "ATTACH_DRAFT", "REOPEN_DRAFT")) {
            history(c.id, action, "CLOSE".equals(action) ? "NO_ACTION" : null, admin, null, null);
        }
        history(c.id, "ASSIGN", null, admin, null, inv.userId());

        JsonNode r = list(inv, since(t0));
        assertThat(types(r)).containsExactly("CASE_ASSIGNED");
        assertThat(r.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void 경계_server_time_을_다음_since_로_주면_경계_초의_행이_한_번만_나온다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        // 곧 부를 조회의 상한(지금 초 버림 − 5초)과 같은 초
        insertHistory(c.id, "ASSIGN", null, admin.userId(), null, inv.userId(), Instant.now().minusSeconds(5));

        JsonNode first = list(inv, since(t0));
        Thread.sleep(1100);                                                // 다음 초로 넘긴다
        JsonNode second = list(inv, first.get("server_time").asText());
        assertThat(first.get("items").size() + second.get("items").size()).isEqualTo(1);
        assertThat(first.get("total").asInt() + second.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void 경계_조회_뒤에_커밋된_행도_다음_조회에서_한_번_나온다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        try (Connection other = dataSource.getConnection()) {   // 다른 트랜잭션 — created_at 은 커밋 전에 찍힌다
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("""
                    insert into ops.case_history (case_id, action, from_status, to_status, case_version_before,
                                                  created_at, created_by, to_assignee_id)
                    values (?, 'ASSIGN', 'OPEN', 'REVIEWING', 0, ?, ?, ?)""")) {
                ps.setLong(1, c.id);
                ps.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(2)));
                ps.setLong(3, admin.userId());
                ps.setLong(4, inv.userId());
                ps.executeUpdate();
            }
            JsonNode first = list(inv, since(t0));                 // 아직 커밋 전이라 안 보임
            other.commit();
            Thread.sleep(4000);                                     // 상한이 그 행을 지나도록
            JsonNode second = list(inv, first.get("server_time").asText());
            assertThat(first.get("items").size() + second.get("items").size()).isEqualTo(1);
            assertThat(caseNos(second)).containsExactly(c.no);
        }
    }

    @Test
    void 경계_server_time_뒤에_찍힌_행은_이번_응답에_없다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        insertHistory(c.id, "ASSIGN", null, admin.userId(), null, inv.userId(), Instant.now().plus(Duration.ofHours(1)));

        JsonNode r = list(inv, since(t0));
        assertThat(r.get("items")).isEmpty();
        assertThat(r.get("total").asInt()).isZero();
    }

    @Test
    void 경계_since_의_나노초는_잘라서_바로_뒤_마이크로초_행이_빠지지_않는다() throws Exception {
        TestCase c = newCase(inv.userId(), false);
        Instant base = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        insertHistory(c.id, "ASSIGN", null, admin.userId(), null, inv.userId(), base.plusNanos(123_457_000));

        JsonNode r = list(inv, base.plusNanos(123_456_900).toString());   // ...:00.123456900Z
        assertThat(types(r)).containsExactly("CASE_ASSIGNED");
        assertThat(r.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void 경계_같은_시각_두_건은_나중_행이_먼저() throws Exception {
        TestCase first = newCase(inv.userId(), false);
        TestCase second = newCase(inv.userId(), false);
        Instant same = t0.plusMillis(500);
        insertHistory(first.id, "ASSIGN", null, admin.userId(), null, inv.userId(), same);
        insertHistory(second.id, "ASSIGN", null, admin.userId(), null, inv.userId(), same);

        JsonNode r = json.readTree(raw(get(URL).param("since", since(t0)).param("size", "1").with(user(inv)), 200));
        assertThat(caseNos(r)).containsExactly(second.no);
        assertThat(r.get("total").asInt()).isEqualTo(2);
    }

    // ── 도우미 ──────────────────────────────────────────────

    private record TestCase(long id, String no, String accountKey, String draftNo) {}

    private JsonNode list(AppUserDetails who, String since) throws Exception {
        MockHttpServletRequestBuilder req = get(URL).with(user(who));
        if (since != null) {
            req.param("since", since);
        }
        return json.readTree(raw(req, 200));
    }

    private String raw(MockHttpServletRequestBuilder req, int status) throws Exception {
        var res = mvc.perform(req).andReturn().getResponse();
        res.setCharacterEncoding("UTF-8");
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        return res.getContentAsString();
    }

    private static String since(Instant t) {
        return t.toString();
    }

    private static List<String> types(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.get("items").forEach(i -> out.add(i.get("type").asText()));
        return out;
    }

    private static List<JsonNode> itemsOf(JsonNode r, String caseNo) {
        List<JsonNode> out = new ArrayList<>();
        r.get("items").forEach(i -> {
            if (caseNo.equals(i.get("case_no").asText())) {
                out.add(i);
            }
        });
        return out;
    }

    private static List<String> caseNos(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.get("items").forEach(i -> out.add(i.get("case_no").asText()));
        return out;
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    private AppUserDetails newUser(String loginId, String role) {
        long id = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1) returning id""", Long.class, loginId, "이름 " + loginId, role);
        return new AppUserDetails(id, loginId, "이름 " + loginId, role);
    }

    /** 케이스 한 건. withDraft 면 초안을 붙여 APPROVED 로(초안 번호 확인용), 아니면 REVIEWING. */
    private TestCase newCase(long assigneeId, boolean withDraft) {
        long n = SEQ.incrementAndGet();
        String accountKey = "010_9000N%05dCD41".formatted(n);
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-03-01', 'RUNNING', 1) returning id""", Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_NTF" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-03-01 00:00:00', '2023-03-01 23:59:59', '2023-03-01 23:59:59',
                        '{}'::jsonb, '2023-03-01')
                returning id""", Long.class, "ALT-20230301-%06d".formatted(n), batchId, ruleId, accountKey);
        String caseNo = "CASE-2023-%06d".formatted(800000 + n);
        long caseId = jdbc.queryForObject("""
                insert into ops.cases (case_no, alert_id, account_key, assignee_id, status, created_by, updated_by)
                values (?, ?, ?, ?, 'REVIEWING', 1, 1) returning id""", Long.class,
                caseNo, alertId, accountKey, assigneeId);
        if (!withDraft) {
            return new TestCase(caseId, caseNo, accountKey, null);
        }
        long draftId = jdbc.queryForObject("select nextval('ops.str_draft_id_seq')", Long.class);
        jdbc.update("""
                insert into ops.str_draft_requests (draft_id, case_id, request_snapshot, created_by, updated_by)
                values (?, ?, '{}'::jsonb, 1, 1)""", draftId, caseId);
        jdbc.update("update ops.str_draft_requests set status = 'SUCCEEDED', finished_at = now() where draft_id = ?",
                draftId);
        jdbc.update("""
                insert into ops.str_drafts (id, case_id, generation_id, generator_version, produced_at, payload,
                                            created_by, updated_by)
                values (?, ?, ?, 'test', '2026-11-03T01:10:00Z', '{"facts":{}}'::jsonb, 1, 1)""",
                draftId, caseId, "g-" + draftId);
        jdbc.update("update ops.cases set status = 'APPROVED', current_draft_id = ? where id = ?", draftId, caseId);
        return new TestCase(caseId, caseNo, accountKey, "DRF-%06d".formatted(draftId));
    }

    /** t0 뒤로 한 줄씩 1ms 씩 늦게 — 순서가 정해지고, 승인자 쪽에서 다른 테스트 행과 섞이지 않는다. */
    private long history(long caseId, String action, String reasonCode, AppUserDetails by, Long fromAssignee,
                         Long toAssignee) {
        Instant at = t0.plusMillis(++tick);
        return insertHistory(caseId, action, reasonCode, by == null ? 1L : by.userId(), fromAssignee, toAssignee, at);
    }

    private void historyAt(long caseId, String action, long toAssignee, Instant at) {
        insertHistory(caseId, action, null, admin.userId(), null, toAssignee, at);
    }

    private long insertHistory(long caseId, String action, String reasonCode, long by, Long fromAssignee,
                               Long toAssignee, Instant at) {
        boolean open = "OPEN".equals(action);
        String comment = switch (action) {
            case "NOTE" -> "메모 본문";
            case "REJECT" -> "반려 코멘트 본문";
            default -> null;
        };
        return jdbc.queryForObject("""
                insert into ops.case_history (case_id, action, from_status, to_status, reason_code, comment,
                                              case_version_before, created_at, created_by,
                                              from_assignee_id, to_assignee_id)
                values (?, ?, ?, 'REVIEWING', ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                caseId, action, open ? null : "REVIEWING", reasonCode, comment, (long) tick + 1000,
                Timestamp.from(at), by, fromAssignee, toAssignee);
    }

    /** V010 전처럼 담당자 칸이 빈 ASSIGN·REASSIGN. 필수 CHECK(NOT VALID)를 잠깐 내렸다 같은 정의로 다시 건다. */
    private void oldAssigneeRows(long caseId) {
        jdbc.execute("alter table ops.case_history drop constraint case_history_assignee_required_ck");
        try {
            history(caseId, "ASSIGN", null, admin, null, null);
            history(caseId, "REASSIGN", null, admin, null, null);
        } finally {
            jdbc.execute("""
                    alter table ops.case_history
                        add constraint case_history_assignee_required_ck check (case
                            when action = 'ASSIGN'   then from_assignee_id is null and to_assignee_id is not null
                            when action = 'REASSIGN' then from_assignee_id is not null and to_assignee_id is not null
                                                          and from_assignee_id <> to_assignee_id
                            else true end) not valid""");
        }
    }
}
