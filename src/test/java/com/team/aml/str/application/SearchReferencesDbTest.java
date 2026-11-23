package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.ReferenceResult;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchMeta;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchRequest;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchResponse;
import com.team.aml.shared.ai.AiDtos.ReferenceSearchStatus;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.domain.InvalidRequestException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.text.Normalizer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 참고 사례 찾기 (SCRUM-354, 계약서 S9) — 실제 PostgreSQL + AiClient 목, 설정 켬. 초안은 SaveStrDraft 로 만든다(VerifyStrDraftDbTest 와 같은 재료).
 * 꺼져 있을 때는 SearchReferencesDisabledTest(DB 없이).
 */
@Testcontainers
@SpringBootTest(properties = "aml.ai.reference-search-enabled=true")
@ActiveProfiles("test")
class SearchReferencesDbTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_backend");
        registry.add("spring.datasource.password", () -> "backend");
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @MockBean AiClient aiClient;
    @Autowired SearchReferences search;
    @Autowired GetStrDraft getDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long otherInvestigator;
    long approver;
    long admin;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("ref-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("ref-inv-other" + n, "INVESTIGATOR");
        approver = user("ref-apr" + n, "APPROVER");
        admin = user("ref-adm" + n, "ADMIN");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-03-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_354', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 900 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    @Test
    void 정상_담당_조사역이_유형과_다듬은_검색어로_찾고_결과와_degraded를_받는다() {
        String draftNo = draftNo();
        when(aiClient.searchReferences(anyString(), any())).thenReturn(found(true));
        login(investigator, "INVESTIGATOR");

        SearchReferences.Result r = search.execute(draftNo, List.of("FAN_IN", "CYCLE"), "  해외 계좌 경유  ");

        ArgumentCaptor<ReferenceSearchRequest> sent = ArgumentCaptor.forClass(ReferenceSearchRequest.class);
        verify(aiClient).searchReferences(eq(draftNo), sent.capture());
        assertThat(sent.getValue().typologies()).containsExactly("FAN_IN", "CYCLE");
        assertThat(sent.getValue().query()).isEqualTo("해외 계좌 경유");
        assertThat(sent.getValue().topK()).isEqualTo(5);
        assertThat(sent.getValue().sourceTypes()).containsExactly("PUBLIC");
        assertThat(r.degraded()).isTrue();
        assertThat(r.results()).extracting(ReferenceResult::docTitle).containsExactly("APG 2025", "FFIEC");
    }

    @Test
    void 정상_승인자도_찾을_수_있고_두_역할_모두_상세에_SEARCH_REFERENCES가_있다() {
        String draftNo = draftNo();
        when(aiClient.searchReferences(anyString(), any())).thenReturn(empty());

        login(approver, "APPROVER");
        assertThat(search.execute(draftNo, List.of("SCATTER_GATHER"), null).results()).isEmpty();
        assertThat(getDraft.execute(draftNo).availableActions()).contains("SEARCH_REFERENCES");

        login(investigator, "INVESTIGATOR");
        assertThat(getDraft.execute(draftNo).availableActions()).contains("SEARCH_REFERENCES");
    }

    // ── 경계 ──────────────────────────────────────────────────

    @Test
    void 경계_공백뿐인_검색어는_null로_보내고_NFD로_쓴_한글_200자는_NFC로_바꿔_보낸다() {
        String draftNo = draftNo();
        when(aiClient.searchReferences(anyString(), any())).thenReturn(empty());
        login(investigator, "INVESTIGATOR");

        search.execute(draftNo, List.of("FAN_OUT"), "   \t ");
        String nfd200 = Normalizer.normalize("가".repeat(200), Normalizer.Form.NFD);   // 원문 400자, NFC 200자
        search.execute(draftNo, List.of("FAN_OUT"), nfd200);

        ArgumentCaptor<ReferenceSearchRequest> sent = ArgumentCaptor.forClass(ReferenceSearchRequest.class);
        verify(aiClient, org.mockito.Mockito.times(2)).searchReferences(eq(draftNo), sent.capture());
        assertThat(sent.getAllValues().get(0).query()).isNull();
        assertThat(sent.getAllValues().get(1).query()).isEqualTo("가".repeat(200));   // NFC 로 보냄(#178 리뷰)
    }

    @Test
    void 경계_NBSP만_있는_검색어는_null로_보내고_앞뒤_NBSP는_지운다() {   // AI str.strip 과 같은 공백 판단(#178 리뷰)
        String draftNo = draftNo();
        when(aiClient.searchReferences(anyString(), any())).thenReturn(empty());
        login(investigator, "INVESTIGATOR");

        search.execute(draftNo, List.of("FAN_IN"), "\u00A0");
        search.execute(draftNo, List.of("FAN_IN"), "\u00A0 해외 경유\u202F");

        ArgumentCaptor<ReferenceSearchRequest> sent = ArgumentCaptor.forClass(ReferenceSearchRequest.class);
        verify(aiClient, org.mockito.Mockito.times(2)).searchReferences(eq(draftNo), sent.capture());
        assertThat(sent.getAllValues().get(0).query()).isNull();
        assertThat(sent.getAllValues().get(1).query()).isEqualTo("해외 경유");
    }

    // ── 실패 ──────────────────────────────────────────────────

    @Test
    void 실패_유형_개수_값_중복과_검색어_201자는_400이고_AI를_부르지_않는다() {
        String draftNo = draftNo();
        login(investigator, "INVESTIGATOR");

        for (List<String> bad : List.of(List.<String>of(), List.of("FAN_IN", "FAN_OUT", "CYCLE", "SCATTER_GATHER"),
                List.of("STRUCTURING"), List.of("FAN_IN", "FAN_IN"))) {
            assertThatThrownBy(() -> search.execute(draftNo, bad, null))
                    .isInstanceOf(InvalidRequestException.class)
                    .satisfies(e -> assertThat(((InvalidRequestException) e).details()).containsKey("typologies"));
        }
        assertThatThrownBy(() -> search.execute(draftNo, null, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> search.execute(draftNo, List.of("FAN_IN"), "가".repeat(201)))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).details()).containsKey("query"));
        verify(aiClient, never()).searchReferences(anyString(), any());
    }

    @Test
    void 실패_담당_아닌_조사역과_관리자는_403_NOT_ASSIGNEE이고_관리자_상세에는_버튼이_없다() {
        String draftNo = draftNo();

        login(otherInvestigator, "INVESTIGATOR");
        assertThatThrownBy(() -> search.execute(draftNo, List.of("FAN_IN"), null))
                .isInstanceOf(NotAssigneeException.class);
        login(admin, "ADMIN");
        assertThatThrownBy(() -> search.execute(draftNo, List.of("FAN_IN"), null))
                .isInstanceOf(NotAssigneeException.class)
                .satisfies(e -> assertThat(((NotAssigneeException) e).code()).isEqualTo("NOT_ASSIGNEE"));
        assertThat(getDraft.execute(draftNo).availableActions()).doesNotContain("SEARCH_REFERENCES");
        verify(aiClient, never()).searchReferences(anyString(), any());
    }

    @Test
    void 실패_없는_초안은_404이고_AI_오류는_그대로_나간다() {
        login(investigator, "INVESTIGATOR");
        assertThatThrownBy(() -> search.execute("DRF-999999999", List.of("FAN_IN"), null))
                .isInstanceOf(DraftNotFoundException.class);
        assertThatThrownBy(() -> search.execute("bad-no", List.of("FAN_IN"), null))
                .isInstanceOf(DraftNotFoundException.class);

        String draftNo = draftNo();
        when(aiClient.searchReferences(anyString(), any()))
                .thenThrow(new AiClientException("AI_TIMEOUT", "AI 응답 시간 초과"));
        login(investigator, "INVESTIGATOR");
        assertThatThrownBy(() -> search.execute(draftNo, List.of("FAN_IN"), null))
                .isInstanceOf(AiClientException.class)
                .satisfies(e -> assertThat(((AiClientException) e).httpStatus()).isEqualTo(502));
    }

    // ── 준비 ──────────────────────────────────────────────────

    private static ReferenceSearchResponse found(boolean degraded) {
        return new ReferenceSearchResponse("2026-11-23T02:10:00Z", ReferenceSearchStatus.FOUND,
                List.of(new ReferenceResult("apg-2025", "APG 2025", "APG", 2025, 53, 0.52, "c1",
                                List.of("FAN_IN"), "PUBLIC"),
                        new ReferenceResult("ffiec", "FFIEC", null, null, 7, 0.41, "c2", List.of("CYCLE"), "PUBLIC")),
                "text-embedding-3-small@1a2b3c4d", new ReferenceSearchMeta(degraded, 10, 420), null);
    }

    private static ReferenceSearchResponse empty() {
        return new ReferenceSearchResponse("2026-11-23T02:10:00Z", ReferenceSearchStatus.EMPTY, List.of(),
                "text-embedding-3-small@1a2b3c4d", new ReferenceSearchMeta(false, 0, 120), null);
    }

    /** 경보 → ESCALATED 케이스(담당 = investigator) → 저장된 초안. 초안 번호(DRF-…)를 돌려준다. */
    private String draftNo() {
        long caseId = escalatedCase();
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", DisplayNumber.draftNo(draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        login(investigator, "INVESTIGATOR");
        saveDraft.execute(caseId, draftId, caseVersion(caseId), response(draftId));
        SecurityContextHolder.clearContext();
        return DisplayNumber.draftNo(draftId);
    }

    private static ObjectNode response(long draftId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", DisplayNumber.draftNo(draftId))
                .put("generation_id", "g-" + draftId);
        ObjectNode facts = r.putObject("facts");
        facts.putObject("subject").put("account_key", "010_8000EBD30");
        facts.putObject("inbound").put("count", 3);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period");
            n.putArray("evidence_ids").add(104221);
        }
        r.putNull("verification");
        return r;
    }

    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2024-03-01 00:00', '2024-03-02 00:00', '2024-03-02 00:00', '{}', '2024-03-01', 'SCORED') returning id",
                Long.class, "ALT-20240301-" + String.format("%06d", 354_000 + n), batchRunId, ruleId, "ACC-R" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 354_000 + n), alertId, "ACC-R" + n, investigator);
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '사용자', ?, 1, 1) returning id", Long.class, loginId, role);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private static void login(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }

}
