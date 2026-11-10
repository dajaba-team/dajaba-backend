package com.team.aml.cases.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** /api/v1/cases — 목록·상세·배정·담당자 변경·메모·에스컬레이션·종료를 HTTP 로 (SCRUM-106·176·177, 계약서 C1·C2·C8·A1·A2·A3·A4·A5). 실제 PostgreSQL. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseApiTest {

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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private AppUserDetails inv;
    private AppUserDetails inv2;

    @BeforeEach
    void users() {
        long n = SEQ.incrementAndGet();
        inv = newUser("api_inv" + n, "INVESTIGATOR");
        inv2 = newUser("api_invb" + n, "INVESTIGATOR");
    }

    @Test
    void 정상_조사역이_맡고_에스컬레이션하고_종료한다_응답에_원본_계좌가_없다() throws Exception {
        String accountKey = "010_8000E%05dBD30".formatted(SEQ.incrementAndGet());
        String caseNo = newOpenCase(accountKey);

        // 목록 — 가린 계좌와 구별용 ref 만
        String listBody = body(get("/api/v1/cases").param("status", "OPEN,REVIEWING").param("size", "100").with(as(inv)), 200);
        JsonNode item = itemOf(json.readTree(listBody), caseNo);
        assertThat(item.get("account_no").asText()).isEqualTo("010_****BD30");
        assertThat(item.get("account_ref").asText()).matches("ACR-[0-9A-F]{12}");
        assertThat(item.get("status").asText()).isEqualTo("OPEN");
        assertThat(item.get("assignee").isNull()).isTrue();
        assertThat(item.has("score")).isTrue();
        assertThat(listBody).doesNotContain(accountKey).doesNotContain("account_key");

        // 배정 → REVIEWING
        JsonNode assigned = json.readTree(body(post("/api/v1/cases/{caseNo}/assign", caseNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}").with(as(inv)).with(csrf()), 200));
        assertThat(assigned.get("status").asText()).isEqualTo("REVIEWING");
        assertThat(assigned.get("assignee").get("login_id").asText()).isEqualTo(inv.loginId());
        assertThat(assigned.get("current_draft_no").isNull()).isTrue();
        assertThat(assigned.get("version").asLong()).isEqualTo(1);
        assertThat(assigned.get("updated_at").asText()).endsWith("Z");

        // 상세 — 같은 account_ref, 버튼, 최근 이력
        String detailBody = body(get("/api/v1/cases/{caseNo}", caseNo).with(as(inv)), 200);
        JsonNode detail = json.readTree(detailBody);
        assertThat(detail.get("account_ref").asText()).isEqualTo(item.get("account_ref").asText());
        assertThat(detail.get("account_no").asText()).isEqualTo("010_****BD30");
        assertThat(texts(detail.get("available_actions"))).contains("ESCALATE", "CLOSE");
        assertThat(detail.get("history").get(0).get("action").asText()).isEqualTo("ASSIGN");
        assertThat(detail.get("history").get(0).get("created_by").asText()).isEqualTo(inv.loginId());
        assertThat(detail.get("prior_alerts").isArray()).isTrue();
        JsonNode alert = detail.get("alert");                 // SCRUM-152 — 연결 경보·점수
        assertThat(alert.get("alert_no").asText()).startsWith("ALT-20230201-");
        assertThat(alert.get("rule_id").asText()).startsWith("R_API_C");
        assertThat(alert.get("batch_date").asText()).isEqualTo("2023-02-01");
        assertThat(alert.get("window_start").asText()).isEqualTo("2023-02-01T00:00:00");
        assertThat(alert.get("transaction_count").asInt()).isZero();
        JsonNode latestScore = detail.get("latest_score");
        assertThat(latestScore.get("score_status").asText()).isEqualTo("PENDING");
        assertThat(latestScore.get("score").isNull()).isTrue();
        assertThat(latestScore.get("score_band").isNull()).isTrue();
        assertThat(detail.get("score").isNull()).isTrue();          // 점수 전이라 맨 위 score 도 null (Q2 MVP)
        assertThat(latestScore.get("reasons").isArray()).isTrue();
        assertThat(detailBody).doesNotContain(accountKey).doesNotContain("account_key");

        // 에스컬레이션 → 종료
        assertThat(json.readTree(body(post("/api/v1/cases/{caseNo}/escalate", caseNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\": \"STR 검토 필요\", \"version\": 1}")
                .with(as(inv)).with(csrf()), 200)).get("status").asText()).isEqualTo("ESCALATED");
        JsonNode closed = json.readTree(body(post("/api/v1/cases/{caseNo}/close", caseNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"close_reason\": \"NO_ACTION\", \"comment\": \"추가 조치 불필요\", \"version\": 2}")
                .with(as(inv)).with(csrf()), 200));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("version").asLong()).isEqualTo(3);

        Map<String, Object> row = jdbc.queryForMap("select status, close_reason from ops.cases where case_no = ?", caseNo);
        assertThat(row).containsEntry("status", "CLOSED").containsEntry("close_reason", "NO_ACTION");
        assertThat(jdbc.queryForList("""
                select h.action from ops.case_history h join ops.cases c on c.id = h.case_id
                where c.case_no = ? order by h.id""", String.class, caseNo))
                .containsExactly("OPEN", "ASSIGN", "ESCALATE", "CLOSE");
    }

    @Test
    void 실패_형식이_틀리면_400_INVALID_REQUEST이고_케이스는_그대로() throws Exception {
        String caseNo = newOpenCase("010_BAD%05d".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);

        assertThat(code(post("/api/v1/cases/{caseNo}/close", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"사유 없음\", \"version\": 1}").with(as(inv)).with(csrf()), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(json.readTree(body(post("/api/v1/cases/{caseNo}/close", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"사유 없음\", \"version\": 1}").with(as(inv)).with(csrf()), 400))
                .path("error").path("details").has("close_reason")).isTrue();   // 요청 JSON 이름 그대로 (SCRUM-167)
        assertThat(code(post("/api/v1/cases/{caseNo}/close", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"close_reason\": \"NOT_A_REASON\", \"comment\": \"x\", \"version\": 1}")
                .with(as(inv)).with(csrf()), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(code(post("/api/v1/cases/{caseNo}/escalate", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"   \", \"version\": 1}").with(as(inv)).with(csrf()), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases").param("size", "101").with(as(inv)), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases").param("status", "NOPE").with(as(inv)), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases").param("sort", "account_key,asc").with(as(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(get("/api/v1/cases").param("from", "2026-11-06").param("to", "2026-11-05").with(as(inv)), 400))
                .isEqualTo("INVALID_REQUEST");

        assertThat(jdbc.queryForMap("select status, version from ops.cases where case_no = ?", caseNo))
                .containsEntry("status", "REVIEWING").containsEntry("version", 1L);
    }

    @Test
    void 실패_업무_규칙_위반은_403_404_409이고_케이스는_그대로() throws Exception {
        String caseNo = newOpenCase("010_RULE%05d".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);

        assertThat(code(post("/api/v1/cases/{caseNo}/escalate", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"남의 케이스\", \"version\": 1}").with(as(inv2)).with(csrf()), 403))
                .isEqualTo("NOT_ASSIGNEE");
        assertThat(code(post("/api/v1/cases/{caseNo}/escalate", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"오래된 화면\", \"version\": 0}").with(as(inv)).with(csrf()), 409))
                .isEqualTo("CONCURRENT_UPDATE");
        assertThat(code(post("/api/v1/cases/{caseNo}/assign", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 1}").with(as(inv)).with(csrf()), 409)).isEqualTo("INVALID_CASE_TRANSITION");
        assertThat(code(get("/api/v1/cases/{caseNo}", "CASE-2099-999999").with(as(inv)), 404))
                .isEqualTo("CASE_NOT_FOUND");
        assertThat(mvc.perform(post("/api/v1/cases/{caseNo}/escalate", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"CSRF 없음\", \"version\": 1}").with(as(inv))).andReturn()
                .getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.queryForMap("select status, version from ops.cases where case_no = ?", caseNo))
                .containsEntry("status", "REVIEWING").containsEntry("version", 1L);
    }

    @Test
    void swagger_목록_파라미터는_쿼리_칸만_보인다_검증용_메서드는_없다() throws Exception {
        // 프론트가 swagger 를 보고 MSW 목업을 맞추므로, CaseListQuery 의 검증 메서드(isPeriodInOrder)가 파라미터로 새지 않아야 한다
        JsonNode params = json.readTree(body(get("/v3/api-docs"), 200)).at("/paths/~1api~1v1~1cases/get/parameters");
        List<String> names = new ArrayList<>();
        params.forEach(p -> names.add(p.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrder("status", "assignee", "from", "to", "sort", "page", "size");
    }

    @Test
    void AI_조사_요약_없으면_null_만들면_201_조회는_is_current_true_담당_외_403_버전_409() throws Exception {
        String caseNo = newOpenCase("010_8000S%05dBD30".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);   // version 1

        JsonNode none = json.readTree(body(get("/api/v1/cases/{caseNo}/summary", caseNo).with(as(inv2)), 200));
        assertThat(none.get("case_no").asText()).isEqualTo(caseNo);
        assertThat(none.get("summary").isNull()).isTrue();

        assertThat(code(post("/api/v1/cases/{caseNo}/refresh-summary", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 1}").with(as(inv2)).with(csrf()), 403)).isEqualTo("NOT_ASSIGNEE");
        assertThat(code(post("/api/v1/cases/{caseNo}/refresh-summary", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0}").with(as(inv)).with(csrf()), 409)).isEqualTo("CONCURRENT_UPDATE");

        // 테스트 프로필은 MockAiClient — 코드 검사 4종 통과
        JsonNode made = json.readTree(body(post("/api/v1/cases/{caseNo}/refresh-summary", caseNo)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1}").with(as(inv)).with(csrf()), 201));
        assertThat(made.get("case_no").asText()).isEqualTo(caseNo);
        assertThat(made.get("case_version").asLong()).isEqualTo(2);
        assertThat(made.get("summary").get("summary_lines").isArray()).isTrue();
        assertThat(made.get("summary").get("is_current").asBoolean()).isTrue();
        assertThat(made.get("summary").get("produced_at").asText()).endsWith("Z");

        JsonNode now = json.readTree(body(get("/api/v1/cases/{caseNo}/summary", caseNo).with(as(inv2)), 200));
        assertThat(now.get("summary").get("is_current").asBoolean()).isTrue();
        assertThat(now.get("summary").get("input_sha256").asText())
                .isEqualTo(made.get("summary").get("input_sha256").asText());
        JsonNode detail = json.readTree(body(get("/api/v1/cases/{caseNo}", caseNo).with(as(inv)), 200));
        assertThat(detail.get("latest_summary").get("is_current").asBoolean()).isTrue();
        assertThat(detail.get("version").asLong()).isEqualTo(2);
    }

    // ── 메모 (SCRUM-176, 계약서 A3·C8) ───────────────────────

    @Test
    void 메모_저장하면_NOTE_이력_version_증가_상세에_보이고_이력은_전후_전문() throws Exception {
        String caseNo = newOpenCase("010_NOTE%05d".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);   // version 1

        JsonNode first = json.readTree(body(notes(caseNo, "거래 목적 확인 필요", 1, inv), 200));
        assertThat(first.get("status").asText()).isEqualTo("REVIEWING");
        assertThat(first.get("version").asLong()).isEqualTo(2);
        assertThat(first.get("updated_at").asText()).endsWith("Z");
        assertThat(json.readTree(body(notes(caseNo, "거래 목적 자료 요청 완료", 2, inv), 200))
                .get("version").asLong()).isEqualTo(3);

        // 같은 내용이면 그대로 — 이력도 version 도 안 바뀜
        assertThat(json.readTree(body(notes(caseNo, "거래 목적 자료 요청 완료", 3, inv), 200))
                .get("version").asLong()).isEqualTo(3);
        // 빈 문자열 = 비우기
        assertThat(json.readTree(body(notes(caseNo, "", 3, inv), 200)).get("version").asLong()).isEqualTo(4);

        JsonNode detail = json.readTree(body(get("/api/v1/cases/{caseNo}", caseNo).with(as(inv)), 200));
        assertThat(detail.get("investigator_notes").asText()).isEmpty();
        assertThat(detail.get("version").asLong()).isEqualTo(4);
        assertThat(texts(detail.get("available_actions"))).contains("UPDATE_NOTES");
        detail.get("history").forEach(h -> assertThat(h.get("action").asText()).isNotEqualTo("NOTE"));   // 전문은 C8

        JsonNode page = json.readTree(body(get("/api/v1/cases/{caseNo}/note-histories", caseNo).with(as(inv2)), 200));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        JsonNode items = page.get("items");
        assertThat(items.get(0).get("before_text").asText()).isEqualTo("거래 목적 자료 요청 완료");
        assertThat(items.get(0).get("after_text").asText()).isEmpty();
        assertThat(items.get(0).get("case_version_before").asLong()).isEqualTo(3);
        assertThat(items.get(0).get("created_by").asText()).isEqualTo(inv.loginId());
        assertThat(items.get(0).get("created_at").asText()).endsWith("Z");
        assertThat(items.get(2).get("before_text").isNull()).isTrue();          // 첫 기록
        assertThat(items.get(2).get("after_text").asText()).isEqualTo("거래 목적 확인 필요");

        // 페이지 경계 — 페이지 밖 직전 메모도 before_text 로
        JsonNode top = json.readTree(body(get("/api/v1/cases/{caseNo}/note-histories", caseNo)
                .param("page", "0").param("size", "1").with(as(inv)), 200));
        assertThat(top.get("items")).hasSize(1);
        assertThat(top.get("items").get(0).get("before_text").asText()).isEqualTo("거래 목적 자료 요청 완료");
        JsonNode middle = json.readTree(body(get("/api/v1/cases/{caseNo}/note-histories", caseNo)
                .param("page", "1").param("size", "1").with(as(inv)), 200));
        assertThat(middle.get("items").get(0).get("before_text").asText()).isEqualTo("거래 목적 확인 필요");
        assertThat(middle.get("items").get(0).get("after_text").asText()).isEqualTo("거래 목적 자료 요청 완료");

        assertThat(jdbc.queryForList("""
                select h.action from ops.case_history h join ops.cases c on c.id = h.case_id
                where c.case_no = ? order by h.id""", String.class, caseNo))
                .containsExactly("OPEN", "ASSIGN", "NOTE", "NOTE", "NOTE");
    }

    @Test
    void 메모_실패는_400_403_404_409이고_아무것도_안_바뀐다() throws Exception {
        String caseNo = newOpenCase("010_NOTX%05d".formatted(SEQ.incrementAndGet()));

        assertThat(code(notes(caseNo, "배정 전", 0, inv), 403)).isEqualTo("NOT_ASSIGNEE");   // 담당 없음 = 담당 아님
        assign(caseNo, inv);   // version 1

        assertThat(code(notes(caseNo, "x".repeat(10001), 1, inv), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(json.readTree(body(put("/api/v1/cases/{caseNo}/notes", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"investigator_notes\": null, \"version\": 1}").with(as(inv)).with(csrf()), 400))
                .path("error").path("details").has("investigator_notes")).isTrue();
        assertThat(code(put("/api/v1/cases/{caseNo}/notes", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 1}").with(as(inv)).with(csrf()), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(code(notes(caseNo, "남의 케이스", 1, inv2), 403)).isEqualTo("NOT_ASSIGNEE");
        assertThat(code(notes(caseNo, "", 0, inv), 409)).isEqualTo("CONCURRENT_UPDATE");   // 내용이 같아도 옛 version 은 409
        assertThat(code(notes("CASE-2099-999999", "x", 0, inv), 404)).isEqualTo("CASE_NOT_FOUND");
        assertThat(code(get("/api/v1/cases/{caseNo}/note-histories", "CASE-2099-999999").with(as(inv)), 404))
                .isEqualTo("CASE_NOT_FOUND");
        assertThat(code(get("/api/v1/cases/{caseNo}/note-histories", caseNo).param("size", "101").with(as(inv)), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForMap("select version from ops.cases where case_no = ?", caseNo))
                .containsEntry("version", 1L);

        // 10,000자 딱은 저장된다
        assertThat(json.readTree(body(notes(caseNo, "가".repeat(10000), 1, inv), 200)).get("version").asLong())
                .isEqualTo(2);

        // 승인 대기처럼 편집 불가 상태는 409 INVALID_CASE_TRANSITION(action=UPDATE_NOTES)
        String other = newOpenCase("010_NOTP%05d".formatted(SEQ.incrementAndGet()));
        assign(other, inv);
        long draftId = newDraft(other);
        jdbc.update("update ops.cases set status = 'PENDING_APPROVAL', current_draft_id = ? where case_no = ?",
                draftId, other);
        JsonNode err = json.readTree(body(notes(other, "승인 대기 중", 1, inv), 409)).get("error");
        assertThat(err.get("code").asText()).isEqualTo("INVALID_CASE_TRANSITION");
        assertThat(err.get("details").get("action").asText()).isEqualTo("UPDATE_NOTES");

        JsonNode empty = json.readTree(body(get("/api/v1/cases/{caseNo}/note-histories", other).with(as(inv)), 200));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("total").asLong()).isZero();
        assertThat(jdbc.queryForMap("select version from ops.cases where case_no = ?", other))
                .containsEntry("version", 1L);
    }

    // ── 담당자 변경 (SCRUM-177, 계약서 A2) ───────────────────

    @Test
    void 담당자_변경_ADMIN이_바꾸면_상태_그대로_REASSIGN_이력에_넘긴_받은_사람() throws Exception {
        AppUserDetails admin = newUser("api_adm" + SEQ.incrementAndGet(), "ADMIN");
        String caseNo = newOpenCase("010_RSGN%05d".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);   // version 1

        // 목록 한 줄에도 상세와 같은 available_actions — 관리자에게만 REASSIGN (#23 리뷰)
        JsonNode adminRow = itemOf(json.readTree(body(get("/api/v1/cases").param("status", "REVIEWING")
                .param("size", "100").with(as(admin)), 200)), caseNo);
        assertThat(texts(adminRow.get("available_actions"))).contains("REASSIGN");
        JsonNode invRow = itemOf(json.readTree(body(get("/api/v1/cases").param("status", "REVIEWING")
                .param("size", "100").with(as(inv)), 200)), caseNo);
        assertThat(texts(invRow.get("available_actions"))).doesNotContain("REASSIGN").contains("ESCALATE");

        JsonNode moved = json.readTree(body(reassign(caseNo, inv2.loginId(), "조사 업무 분담", 1, admin), 200));
        assertThat(moved.get("status").asText()).isEqualTo("REVIEWING");
        assertThat(moved.get("assignee").get("login_id").asText()).isEqualTo(inv2.loginId());
        assertThat(moved.get("version").asLong()).isEqualTo(2);

        // 같은 담당자면 그대로 — 이력도 version 도 안 바뀜
        assertThat(json.readTree(body(reassign(caseNo, inv2.loginId(), null, 2, admin), 200))
                .get("version").asLong()).isEqualTo(2);

        Map<String, Object> h = jdbc.queryForMap("""
                select h.action, h.from_status, h.to_status, h.comment, h.from_assignee_id, h.to_assignee_id, h.created_by
                from ops.case_history h join ops.cases c on c.id = h.case_id
                where c.case_no = ? order by h.id desc limit 1""", caseNo);
        assertThat(h).containsEntry("action", "REASSIGN").containsEntry("from_status", "REVIEWING")
                .containsEntry("to_status", "REVIEWING").containsEntry("comment", "조사 업무 분담")
                .containsEntry("from_assignee_id", inv.userId()).containsEntry("to_assignee_id", inv2.userId())
                .containsEntry("created_by", admin.userId());
        assertThat(jdbc.queryForObject("""
                select count(*) from ops.case_history h join ops.cases c on c.id = h.case_id
                where c.case_no = ? and h.action = 'REASSIGN'""", Long.class, caseNo)).isEqualTo(1);

        // 넘긴 조사역은 이제 담당이 아니다
        assertThat(code(post("/api/v1/cases/{caseNo}/escalate", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\": \"넘긴 뒤\", \"version\": 2}").with(as(inv)).with(csrf()), 403))
                .isEqualTo("NOT_ASSIGNEE");
        JsonNode detail = json.readTree(body(get("/api/v1/cases/{caseNo}", caseNo).with(as(admin)), 200));
        assertThat(texts(detail.get("available_actions"))).contains("REASSIGN");
    }

    @Test
    void 담당자_변경_실패는_400_403_404_409이고_케이스는_그대로() throws Exception {
        AppUserDetails admin = newUser("api_adm" + SEQ.incrementAndGet(), "ADMIN");
        AppUserDetails apr = newUser("api_apr" + SEQ.incrementAndGet(), "APPROVER");
        AppUserDetails retired = newUser("api_old" + SEQ.incrementAndGet(), "INVESTIGATOR");
        jdbc.update("update ops.users set is_active = false where id = ?", retired.userId());
        String open = newOpenCase("010_RSGO%05d".formatted(SEQ.incrementAndGet()));
        String caseNo = newOpenCase("010_RSGX%05d".formatted(SEQ.incrementAndGet()));
        assign(caseNo, inv);   // version 1

        assertThat(code(reassign(caseNo, inv2.loginId(), null, 1, inv), 403)).isEqualTo("FORBIDDEN");   // 조사역
        assertThat(code(reassign(caseNo, inv2.loginId(), null, 1, apr), 403)).isEqualTo("FORBIDDEN");   // 승인자
        assertThat(code(reassign(caseNo, "", null, 1, admin), 400)).isEqualTo("INVALID_REQUEST");
        assertThat(code(reassign(caseNo, inv2.loginId(), "x".repeat(2001), 1, admin), 400))
                .isEqualTo("INVALID_REQUEST");
        assertThat(code(reassign(caseNo, inv2.loginId(), null, 0, admin), 409)).isEqualTo("CONCURRENT_UPDATE");
        assertThat(code(reassign(caseNo, "nobody", null, 1, admin), 404)).isEqualTo("USER_NOT_FOUND");
        JsonNode inactive = json.readTree(body(reassign(caseNo, retired.loginId(), null, 1, admin), 409)).get("error");
        assertThat(inactive.get("code").asText()).isEqualTo("INVALID_ASSIGNEE");
        assertThat(inactive.get("details").get("reason").asText()).isEqualTo("NOT_ACTIVE_INVESTIGATOR");
        assertThat(code(reassign(caseNo, apr.loginId(), null, 1, admin), 409)).isEqualTo("INVALID_ASSIGNEE");
        assertThat(code(reassign(open, inv2.loginId(), null, 0, admin), 409)).isEqualTo("INVALID_CASE_TRANSITION");
        assertThat(code(reassign("CASE-2099-999999", inv2.loginId(), null, 0, admin), 404)).isEqualTo("CASE_NOT_FOUND");

        assertThat(jdbc.queryForMap("select status, version from ops.cases where case_no = ?", caseNo))
                .containsEntry("status", "REVIEWING").containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("select assignee_id from ops.cases where case_no = ?", Long.class, caseNo))
                .isEqualTo(inv.userId());
    }

    // ── 준비 ──────────────────────────────────────────────────

    private RequestBuilder reassign(String caseNo, String assignee, String comment, long version,
                                    AppUserDetails who) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("assignee", assignee);
        if (comment != null) {
            body.put("comment", comment);
        }
        body.put("version", version);
        return post("/api/v1/cases/{caseNo}/reassign", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)).with(as(who)).with(csrf());
    }


    private RequestBuilder notes(String caseNo, String text, long version, AppUserDetails who) throws Exception {
        String content = json.writeValueAsString(Map.of("investigator_notes", text, "version", version));
        return put("/api/v1/cases/{caseNo}/notes", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content(content).with(as(who)).with(csrf());
    }

    /** 승인 대기로 만들 때 필요한 초안 한 건 (V006 cases_current_draft_ck). */
    private long newDraft(String caseNo) {
        long caseId = jdbc.queryForObject("select id from ops.cases where case_no = ?", Long.class, caseNo);
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
        return draftId;
    }


    private void assign(String caseNo, AppUserDetails who) throws Exception {
        body(post("/api/v1/cases/{caseNo}/assign", caseNo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": 0}").with(as(who)).with(csrf()), 200);
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

    private static JsonNode itemOf(JsonNode page, String caseNo) {
        for (JsonNode item : page.get("items")) {
            if (caseNo.equals(item.get("case_no").asText())) {
                return item;
            }
        }
        throw new AssertionError("목록에 " + caseNo + " 가 없습니다: " + page);
    }

    private static java.util.List<String> texts(JsonNode array) {
        java.util.List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
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

    /** OPEN 케이스 + OPEN 이력. 엔티티 대신 SQL — api 테스트가 리포지토리에 기대지 않게. */
    private String newOpenCase(String accountKey) {
        long n = SEQ.incrementAndGet();
        long batchId = jdbc.queryForObject("""
                insert into ops.batch_runs (batch_type, batch_date, status, triggered_by)
                values ('RULE_SCAN', '2023-02-01', 'RUNNING', 1) returning id""", Long.class);
        long ruleId = jdbc.queryForObject("""
                insert into ops.rules (rule_id, version, name, params, created_by, updated_by)
                values (?, 1, 'test', '{}'::jsonb, 1, 1) returning id""", Long.class, "R_API_C" + n);
        long alertId = jdbc.queryForObject("""
                insert into ops.alerts (alert_no, batch_run_id, rule_ref_id, account_key, window_start, window_end,
                                        reference_ts, rule_facts, batch_date)
                values (?, ?, ?, ?, '2023-02-01 00:00:00', '2023-02-01 23:59:59', '2023-02-01 23:59:59',
                        '{}'::jsonb, '2023-02-01')
                returning id""", Long.class, "ALT-20230201-%06d".formatted(n), batchId, ruleId, accountKey);
        String caseNo = "CASE-2023-%06d".formatted(900000 + n);
        long caseId = jdbc.queryForObject("""
                insert into ops.cases (case_no, alert_id, account_key, status, created_by, updated_by)
                values (?, ?, ?, 'OPEN', 1, 1) returning id""", Long.class, caseNo, alertId, accountKey);
        jdbc.update("""
                insert into ops.case_history (case_id, action, to_status, case_version_before, created_by)
                values (?, 'OPEN', 'OPEN', 0, 1)""", caseId);
        return caseNo;
    }
}
