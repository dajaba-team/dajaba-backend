package com.team.aml.cases.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.AskKind;
import com.team.aml.shared.ai.AiDtos.AskRequest;
import com.team.aml.shared.ai.AiDtos.AskResponse;
import com.team.aml.shared.ai.AiDtos.AskStatus;
import com.team.aml.shared.ai.AiDtos.OutOfScopeReason;
import com.team.aml.shared.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 질문 답변 A8·C9 (계약서 5.2·5.3, 설계 문서 7·12.3절 백엔드 줄, SCRUM-302) — 실제 PostgreSQL + MockMvc.
 * 켜기 설정을 켠 컨텍스트. AI 는 @MockBean — 응답 칸 확인(requireAskResponse)은 WebClientAiClientTest 몫이라 여기선 record 만 준다.
 * 꺼진 상태(기본)는 CaseApiTest·CaseQuestionControllerTest.
 */
@Testcontainers
@SpringBootTest(properties = "aml.ai.ask-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseQuestionApiTest {

    private static final AtomicLong SEQ = new AtomicLong();

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

    @MockBean AiClient aiClient;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private AppUserDetails inv;
    private AppUserDetails inv2;
    private AppUserDetails apr;
    private AppUserDetails admin;

    @BeforeEach
    void setUp() {
        long n = SEQ.incrementAndGet();
        inv = newUser("q_inv" + n, "INVESTIGATOR");
        inv2 = newUser("q_invb" + n, "INVESTIGATOR");
        apr = newUser("q_apr" + n, "APPROVER");
        admin = newUser("q_adm" + n, "ADMIN");
        reset(aiClient);
    }

    // ── 정상 ──────────────────────────────────────────────────

    @Test
    void 정상_담당_조사역이_물으면_201_질문_한_줄이고_요약과_같은_입력에_질문을_자르지_않고_보낸다() throws Exception {
        Fixture f = newCase(inv);
        String question = "  입금 상대가 몇 곳이야? ";   // 앞뒤 공백 그대로
        ArgumentCaptor<AskRequest> sent = ArgumentCaptor.forClass(AskRequest.class);
        when(aiClient.ask(sent.capture())).thenAnswer(i -> {
            // AI 호출은 트랜잭션 밖 (01_code_rules §4)
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return answered(i.getArgument(0), "입금 상대 계좌는 2개예요.", List.of(f.tx1()));
        });

        JsonNode res = json.readTree(body(ask(f.caseNo(), question, inv), 201));

        AskRequest req = sent.getValue();
        assertThat(req.caseNo()).isEqualTo(f.caseNo());
        assertThat(req.question()).isEqualTo(question);
        assertThat(req.evidenceTransactionIds()).containsExactly(f.tx1(), f.tx2());   // 요약 요청과 같은 조립(오름차순)
        assertThat(req.reasons().isArray()).isTrue();                                // 점수 없으면 []

        assertThat(res.get("question_no").asText()).matches("QST-\\d{6,}");
        assertThat(res.get("case_no").asText()).isEqualTo(f.caseNo());
        assertThat(res.get("question").asText()).isEqualTo(question);
        assertThat(res.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(res.get("out_of_scope_reason").isNull()).isTrue();
        assertThat(res.get("answer").asText()).isEqualTo("입금 상대 계좌는 2개예요.");
        assertThat(longs(res.get("evidence_transaction_ids"))).containsExactly(f.tx1());
        assertThat(res.get("asked_by").get("login_id").asText()).isEqualTo(inv.loginId());
        assertThat(res.get("asked_by").get("display_name").asText()).isEqualTo(inv.displayName());
        assertThat(res.get("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(res.has("cites")).isFalse();          // 화면에 fact 키·코드 검사·kind 는 안 준다
        assertThat(res.has("code_checks")).isFalse();
        assertThat(res.has("kind")).isFalse();
        assertThat(res.has("id")).isFalse();

        Map<String, Object> row = jdbc.queryForMap("""
                select q.question, q.status, q.created_by, q.ai_generation_id, cardinality(q.evidence_transaction_ids) n
                from ops.case_questions q join ops.cases c on c.id = q.case_id where c.case_no = ?""", f.caseNo());
        assertThat(row).containsEntry("question", question).containsEntry("status", "ANSWERED")
                .containsEntry("created_by", inv.userId()).containsEntry("ai_generation_id", "g-test")
                .containsEntry("n", 1);
        // 케이스는 그대로 — version·이력 안 바뀜
        assertThat(jdbc.queryForObject("select version from ops.cases where case_no = ?", Long.class, f.caseNo()))
                .isEqualTo(f.version());
    }

    @Test
    void 정상_승인자는_담당자_없는_OPEN에도_묻고_답을_못한_세_상태도_201이며_기록이_남는다() throws Exception {
        Fixture f = newCase(null);
        when(aiClient.ask(any())).thenAnswer(i -> outOfScope(i.getArgument(0), OutOfScopeReason.JUDGMENT));
        JsonNode oos = json.readTree(body(ask(f.caseNo(), "자금세탁 맞아?", apr), 201));
        assertThat(oos.get("status").asText()).isEqualTo("OUT_OF_SCOPE");
        assertThat(oos.get("out_of_scope_reason").asText()).isEqualTo("JUDGMENT");
        assertThat(oos.get("answer").isNull()).isTrue();
        assertThat(oos.get("evidence_transaction_ids").isEmpty()).isTrue();

        for (AskStatus status : List.of(AskStatus.NO_DATA, AskStatus.UNVERIFIED)) {
            when(aiClient.ask(any())).thenAnswer(i -> unanswered(i.getArgument(0), status));
            JsonNode r = json.readTree(body(ask(f.caseNo(), "EUR 로 들어온 건 얼마야?", apr), 201));
            assertThat(r.get("status").asText()).isEqualTo(status.name());
            assertThat(r.get("answer").isNull()).isTrue();
        }
        assertThat(questionCount(f.caseNo())).isEqualTo(3);
    }

    @Test
    void 정상_지난_질문은_최신_먼저_공통_목록이고_한_줄_모양은_A8과_같다() throws Exception {
        Fixture f = newCase(inv);
        when(aiClient.ask(any())).thenAnswer(i -> unanswered(i.getArgument(0), AskStatus.NO_DATA));
        String first = json.readTree(body(ask(f.caseNo(), "첫 질문", inv), 201)).get("question_no").asText();
        JsonNode second = json.readTree(body(ask(f.caseNo(), "둘째 질문", apr), 201));

        JsonNode page = json.readTree(body(get("/api/v1/cases/{caseNo}/questions", f.caseNo())
                .param("size", "1").with(user(inv)), 200));
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0)).isEqualTo(second);   // 최신 먼저, A8 응답과 같은 모양
        JsonNode next = json.readTree(body(get("/api/v1/cases/{caseNo}/questions", f.caseNo())
                .param("page", "1").param("size", "1").with(user(apr)), 200));
        assertThat(next.get("items").get(0).get("question_no").asText()).isEqualTo(first);
        assertThat(next.get("items").get(0).get("asked_by").get("login_id").asText()).isEqualTo(inv.loginId());
    }

    @Test
    void 정상_상세_available_actions의_ASK_QUESTION은_담당_조사역과_승인자에게만() throws Exception {
        Fixture f = newCase(inv);
        assertThat(actions(f.caseNo(), inv)).contains("ASK_QUESTION");
        assertThat(actions(f.caseNo(), apr)).contains("ASK_QUESTION");
        assertThat(actions(f.caseNo(), inv2)).doesNotContain("ASK_QUESTION");
        assertThat(actions(f.caseNo(), admin)).doesNotContain("ASK_QUESTION");
        Fixture open = newCase(null);
        assertThat(actions(open.caseNo(), apr)).contains("ASK_QUESTION");
        assertThat(actions(open.caseNo(), inv)).doesNotContain("ASK_QUESTION");
        // 목록 한 줄 버튼에는 없다(상세만, 계약서 C2)
        String list = body(get("/api/v1/cases").param("size", "100").with(user(inv)), 200);
        assertThat(list).doesNotContain("ASK_QUESTION");
    }

    // ── 경계 ──────────────────────────────────────────────────

    @Test
    void 경계_질문_300자는_되고_301자_공백만_없음은_400_details_question이며_AI를_부르지_않는다() throws Exception {
        Fixture f = newCase(inv);
        when(aiClient.ask(any())).thenAnswer(i -> unanswered(i.getArgument(0), AskStatus.NO_DATA));

        body(ask(f.caseNo(), "가".repeat(300), inv), 201);
        // NFD 로 쪼갠 한글 300자(원문 600 UTF-16) — NFC 로 세면 300자라 통과 (화면·AI 와 같은 세기)
        body(ask(f.caseNo(), Normalizer.normalize("한".repeat(300), Normalizer.Form.NFD), inv), 201);
        verify(aiClient, times(2)).ask(any());

        reset(aiClient);
        for (String bad : List.of("가".repeat(301), "😀".repeat(151), "", "   ", " 　\t")) {
            JsonNode err = json.readTree(body(ask(f.caseNo(), bad, inv), 400)).get("error");
            assertThat(err.get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(err.get("details").has("question")).as(bad).isTrue();
        }
        JsonNode missing = json.readTree(body(post("/api/v1/cases/{caseNo}/questions", f.caseNo())
                .contentType(MediaType.APPLICATION_JSON).content("{}").with(user(inv)).with(csrf()), 400));
        assertThat(missing.get("error").get("details").has("question")).isTrue();
        verify(aiClient, never()).ask(any());
        assertThat(questionCount(f.caseNo())).isEqualTo(2);
    }

    // ── 실패 ──────────────────────────────────────────────────

    @Test
    void 실패_담당_아닌_조사역은_403_NOT_ASSIGNEE_관리자는_403_FORBIDDEN_A8_C9_둘_다() throws Exception {
        Fixture f = newCase(inv);
        Fixture open = newCase(null);
        assertThat(code(ask(f.caseNo(), "몇 건이야?", inv2), 403)).isEqualTo("NOT_ASSIGNEE");
        assertThat(code(ask(open.caseNo(), "몇 건이야?", inv), 403)).isEqualTo("NOT_ASSIGNEE");   // 담당자 없는 OPEN
        assertThat(code(ask(f.caseNo(), "몇 건이야?", admin), 403)).isEqualTo("FORBIDDEN");
        assertThat(code(get("/api/v1/cases/{caseNo}/questions", f.caseNo()).with(user(inv2)), 403))
                .isEqualTo("NOT_ASSIGNEE");
        assertThat(code(get("/api/v1/cases/{caseNo}/questions", f.caseNo()).with(user(admin)), 403))
                .isEqualTo("FORBIDDEN");
        assertThat(code(ask("CASE-2099-999999", "몇 건이야?", inv), 404)).isEqualTo("CASE_NOT_FOUND");
        verify(aiClient, never()).ask(any());
        assertThat(questionCount(f.caseNo())).isZero();
    }

    @Test
    void 실패_한_사람이_60초에_5번_물으면_6번째는_429_QUESTION_RATE_LIMITED_다른_케이스도_같이_센다() throws Exception {
        Fixture a = newCase(inv);
        Fixture b = newCase(inv);
        when(aiClient.ask(any())).thenAnswer(i -> unanswered(i.getArgument(0), AskStatus.NO_DATA));
        for (int i = 0; i < 5; i++) {
            body(ask((i % 2 == 0 ? a : b).caseNo(), "질문 " + i, inv), 201);
        }

        JsonNode err = json.readTree(body(ask(a.caseNo(), "여섯째", inv), 429)).get("error");
        assertThat(err.get("code").asText()).isEqualTo("QUESTION_RATE_LIMITED");
        assertThat(err.get("details").get("retry_after_seconds").asLong()).isBetween(1L, 60L);
        assertThat(code(ask(b.caseNo(), "다른 케이스", inv), 429)).isEqualTo("QUESTION_RATE_LIMITED");
        verify(aiClient, times(5)).ask(any());
        // 다른 사람은 따로 센다
        body(ask(a.caseNo(), "승인자 질문", apr), 201);
    }

    @Test
    void 실패_AI_연동이_실패하면_502_그대로이고_질문_기록이_남지_않는다() throws Exception {
        Fixture f = newCase(inv);
        when(aiClient.ask(any())).thenThrow(new AiClientException("AI_BAD_RESPONSE", "evidence_ids", Map.of(), 502));
        assertThat(code(ask(f.caseNo(), "몇 건이야?", inv), 502)).isEqualTo("AI_BAD_RESPONSE");
        when(aiClient.ask(any())).thenThrow(new AiClientException("AI_TIMEOUT", "timeout", Map.of(), 502));
        assertThat(code(ask(f.caseNo(), "몇 건이야?", inv), 502)).isEqualTo("AI_TIMEOUT");

        assertThat(questionCount(f.caseNo())).isZero();
        JsonNode page = json.readTree(body(get("/api/v1/cases/{caseNo}/questions", f.caseNo()).with(user(inv)), 200));
        assertThat(page.get("total").asLong()).isZero();
        assertThat(page.get("items").isEmpty()).isTrue();
    }

    // ── 도우미 ────────────────────────────────────────────────

    private record Fixture(String caseNo, long version, long tx1, long tx2) {}

    /** 경보(거래 2건) + 케이스. assignee 가 있으면 REVIEWING, 없으면 OPEN. 엔티티 대신 SQL (CaseApiTest 와 같음). */
    private Fixture newCase(AppUserDetails assignee) {
        long n = SEQ.incrementAndGet();
        String accountKey = "10_Q%06d".formatted(n);
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-03-01', 'RUNNING', 1) returning id""", Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, typology_hint, params, created_by, updated_by)
                values (?, 1, 'test', 'FAN_IN', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_Q" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                        window_end, reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, ?, '2023-03-01 00:00:00', '2023-03-01 23:59:59', '2023-03-01 23:59:59',
                        '{"inbound_count": 2}'::jsonb, '2023-03-01')
                returning id""", Long.class, "ALT-20230301-%06d".formatted(n), batchId, ruleId, accountKey, "E_Q" + n);
        long t2 = transaction(n * 10 + 2);
        long t1 = transaction(n * 10 + 1);
        jdbc.update("insert into ops.alert_transactions (alert_id, transaction_id) values (?, ?), (?, ?)",
                alertId, t1, alertId, t2);
        String caseNo = "CASE-2023-%06d".formatted(700000 + n);
        long caseId = jdbc.queryForObject("""
                insert into ops.cases (case_no, alert_id, account_key, entity_id, status, assignee_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 1, 1) returning id""", Long.class, caseNo, alertId, accountKey, "E_Q" + n,
                assignee == null ? "OPEN" : "REVIEWING", assignee == null ? null : assignee.userId());
        jdbc.update("""
                insert into ops.case_history (case_id, action, to_status, case_version_before, created_by)
                values (?, 'OPEN', 'OPEN', 0, 1)""", caseId);
        return new Fixture(caseNo, 0, Math.min(t1, t2), Math.max(t1, t2));
    }

    private long transaction(long n) {
        long fileId = jdbc.queryForObject(
                "insert into ops.source_files (file_name, file_hash) values ('q.csv', ?) returning id",
                Long.class, String.format("%064d", 700000 + n));
        return jdbc.queryForObject("""
                insert into ops.transactions (ts, from_bank_id, from_account_no, from_account_key, to_bank_id,
                    to_account_no, to_account_key, amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number,
                    from_bank_id_raw, to_bank_id_raw)
                values ('2023-03-01 10:00', '10', 'A', '10_A', '20', 'B', '20_B', 1, 'US Dollar', 1, 'US Dollar',
                        'ACH', false, false, ?, 1, '010', '020') returning transaction_id""", Long.class, fileId);
    }

    private static AskResponse answered(AskRequest req, String answer, List<Long> evidence) {
        return new AskResponse("2026-11-19T09:00:00Z", req.caseNo(), "g-test", AskStatus.ANSWERED, AskKind.FACT,
                null, answer, List.of("counterparties.count"), evidence, null, null, null);
    }

    private static AskResponse unanswered(AskRequest req, AskStatus status) {
        return new AskResponse("2026-11-19T09:00:00Z", req.caseNo(), "g-test", status, AskKind.FACT,
                null, null, List.of(), List.of(), null, null, null);
    }

    private static AskResponse outOfScope(AskRequest req, OutOfScopeReason reason) {
        return new AskResponse("2026-11-19T09:00:00Z", req.caseNo(), "g-test", AskStatus.OUT_OF_SCOPE, null,
                reason, null, List.of(), List.of(), null, null, null);
    }

    private RequestBuilder ask(String caseNo, String question, AppUserDetails who) throws Exception {
        return post("/api/v1/cases/{caseNo}/questions", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("question", question))).with(as(who)).with(csrf());
    }

    private List<String> actions(String caseNo, AppUserDetails who) throws Exception {
        List<String> out = new ArrayList<>();
        json.readTree(body(get("/api/v1/cases/{caseNo}", caseNo).with(as(who)), 200))
                .get("available_actions").forEach(a -> out.add(a.asText()));
        return out;
    }

    private long questionCount(String caseNo) {
        return jdbc.queryForObject("""
                select count(*) from ops.case_questions q join ops.cases c on c.id = q.case_id
                where c.case_no = ?""", Long.class, caseNo);
    }

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        response.setCharacterEncoding("UTF-8");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return response.getContentAsString();
    }

    private String code(RequestBuilder request, int expectedStatus) throws Exception {
        return json.readTree(body(request, expectedStatus)).get("error").get("code").asText();
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private static RequestPostProcessor as(AppUserDetails u) {
        return user(u);
    }

    private AppUserDetails newUser(String loginId, String role) {
        long id = jdbc.queryForObject("""
                insert into ops.users (login_id, display_name, role, created_by, updated_by)
                values (?, ?, ?, 1, 1) returning id""", Long.class, loginId, "이름 " + loginId, role);
        return new AppUserDetails(id, loginId, "이름 " + loginId, role);
    }
}
