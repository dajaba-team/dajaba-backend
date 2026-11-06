package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.NotAssigneeException;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.domain.DraftRequestClosedException;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrDraftRequestStatus;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.str.domain.StrSection;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SaveStrDraft — AI 초안 응답 저장 + 케이스 연결을 실제 PostgreSQL(V001~V009)에서 확인한다.
 * 앱은 app_backend 계정(StrDraftRepositoryTest 와 같음). 담당 조사역으로 로그인한 상태에서 부른다(AttachDraftToCase 가 담당자를 본다).
 * AI 응답은 규약 5.2 모양(7항목, cites 포함)을 손으로 만든다 — AI 를 부르지 않는다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SaveStrDraftDbTest {

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

    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRepository drafts;
    @Autowired StrDraftRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long otherInvestigator;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("save-inv" + n);
        otherInvestigator = user("save-inv-other" + n);
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2023-01-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 300 + n);
        login(investigator);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // 1
    @Test
    void 첫_초안을_저장하면_초안_ACTIVE_수정본_1번_케이스_DRAFTED_요청_SUCCEEDED() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);

        SaveStrDraft.Saved saved = saveDraft.execute(caseId, draftId, version, response(draftId, "g-" + draftId));

        assertThat(saved.draftId()).isEqualTo(draftId);
        assertThat(saved.caseVersion()).isEqualTo(caseVersion(caseId)).isEqualTo(version + 1);   // SCRUM-141
        tx.executeWithoutResult(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            assertThat(draft.getStatus()).isEqualTo(StrDraftStatus.ACTIVE);
            assertThat(draft.getGenerationId()).isEqualTo("g-" + draftId);
            assertThat(draft.getGeneratorVersion()).isEqualTo("narrative_v2");          // generation_meta.prompt_version
            assertThat(draft.getProducedAt()).isEqualTo(Instant.parse("2026-11-03T01:10:00Z"));
            assertThat(draft.getFacts().path("inbound").path("count").asInt()).isEqualTo(3);   // generated column
            assertThat(draft.getCurrentRevision().getRevisionNo()).isEqualTo(1);
            assertThat(draft.getCurrentRevision().getRevisedBy()).isEqualTo(1L);          // AI 원문 = SYSTEM
            assertThat(draft.currentBody()).extracting(NarrativeSection::section).containsExactlyElementsOf(StrSection.V2);
            assertThat(draft.currentBody()).allSatisfy(n -> assertThat(n.cites()).containsExactly("period", "inbound.count"));
            assertThat(draft.getCurrentRevisionId()).isEqualTo(draft.getCurrentRevision().getId());
        });
        assertThat(jdbc.queryForMap("select status, current_draft_id from ops.cases where id = ?", caseId))
                .containsEntry("status", "DRAFTED").containsEntry("current_draft_id", draftId);
        assertThat(requestStatus(draftId)).isEqualTo(StrDraftRequestStatus.SUCCEEDED.name());
        assertThat(jdbc.queryForObject("select finished_at is not null from ops.str_draft_requests where draft_id = ?",
                Boolean.class, draftId)).isTrue();
    }

    // 2
    @Test
    void 다시_만들면_예전_초안은_SUPERSEDED_새_초안이_ACTIVE이고_케이스는_새_초안을_가리킨다() {
        long caseId = escalatedCase();
        long first = requested(caseId);
        long v1 = saveDraft.execute(caseId, first, caseVersion(caseId), response(first, "g-" + first)).caseVersion();

        long second = requested(caseId);
        SaveStrDraft.Saved saved = saveDraft.execute(caseId, second, v1, response(second, "g-" + second));

        assertThat(draftStatus(first)).isEqualTo("SUPERSEDED");
        assertThat(draftStatus(second)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("select status, current_draft_id from ops.cases where id = ?", caseId))
                .containsEntry("status", "DRAFTED").containsEntry("current_draft_id", second);
        assertThat(saved.caseVersion()).isEqualTo(caseVersion(caseId)).isEqualTo(v1 + 1);
    }

    // 3
    @Test
    void AI_기다리는_사이_케이스가_바뀌었으면_409이고_아무것도_저장하지_않는다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version - 1, response(draftId, "g-" + draftId)))
                .isInstanceOf(OptimisticLockException.class);

        assertNothingSaved(caseId, draftId, version);
    }

    // 4
    @Test
    void 담당이_아니면_403이고_아무것도_저장하지_않는다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);
        login(otherInvestigator);

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version, response(draftId, "g-" + draftId)))
                .isInstanceOf(NotAssigneeException.class);

        assertNothingSaved(caseId, draftId, version);
    }

    // 5
    @Test
    void AI_응답의_draft_id가_보낸_것과_다르면_거부하고_롤백한다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version, response(draftId + 1, "g-" + draftId)))
                .isInstanceOfSatisfying(AiClientException.class, SaveStrDraftDbTest::isAiBadResponse);   // AiDraftResponseReader

        assertNothingSaved(caseId, draftId, version);
    }

    // 6: 끝난 요청(만료·실패)에 늦게 온 응답은 저장하지 않는다 (미결 21 ⑤)
    @Test
    void 이미_끝난_요청이면_거부하고_롤백한다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);
        tx.executeWithoutResult(s -> {
            StrDraftRequest r = requests.findById(draftId).orElseThrow();
            r.expire(Instant.now());
            requests.save(r);
        });

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version, response(draftId, "g-" + draftId)))
                .isInstanceOfSatisfying(DraftRequestClosedException.class,      // 500 이 아니라 409
                        e -> assertThat(e.code()).isEqualTo("DRAFT_REQUEST_CLOSED"));

        assertThat(requestStatus(draftId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from ops.str_drafts where id = ?", Long.class, draftId)).isZero();
        assertThat(caseVersion(caseId)).isEqualTo(version);
    }

    // 7
    @Test
    void 다른_케이스의_요청_번호면_거부한다() {
        long caseId = escalatedCase();
        long otherCase = escalatedCase();
        long version = caseVersion(caseId);
        long otherDraft = requested(otherCase);

        assertThatThrownBy(() -> saveDraft.execute(caseId, otherDraft, version, response(otherDraft, "g-" + otherDraft)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(requestStatus(otherDraft)).isEqualTo("REQUESTED");
        assertThat(caseVersion(caseId)).isEqualTo(version);
    }

    // 8
    @Test
    void 단락이_7항목_한_세트가_아니면_AI_BAD_RESPONSE이고_롤백한다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);
        ObjectNode bad = response(draftId, "g-" + draftId);
        ((ObjectNode) bad.withArray("narrative").get(0)).put("section", "SUMMARY");   // SUBJECT 대신 6섹션 이름

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version, bad))
                .isInstanceOfSatisfying(AiClientException.class, SaveStrDraftDbTest::isAiBadResponse);   // 500 아님

        assertNothingSaved(caseId, draftId, version);
    }

    // 9: 응답 읽기는 통과하지만 초안 문장 규칙(InvalidSectionTextException)에 걸리는 응답도 AI 문제 — 409 가 아니라 502
    @Test
    void 단락_문장이_비어_있으면_AI_BAD_RESPONSE이고_롤백한다() {
        long caseId = escalatedCase();
        long version = caseVersion(caseId);
        long draftId = requested(caseId);
        ObjectNode bad = response(draftId, "g-" + draftId);
        ((ObjectNode) bad.withArray("narrative").get(0)).put("text", " ");

        assertThatThrownBy(() -> saveDraft.execute(caseId, draftId, version, bad))
                .isInstanceOfSatisfying(AiClientException.class, e -> {
                    isAiBadResponse(e);
                    assertThat(e.getMessage()).doesNotContain("SUBJECT");   // 무엇이 틀렸는지는 로그로만
                });

        assertNothingSaved(caseId, draftId, version);
    }

    // ---- 도우미 ----

    private static void isAiBadResponse(AiClientException e) {
        assertThat(e.code()).isEqualTo("AI_BAD_RESPONSE");
        assertThat(e.httpStatus()).isEqualTo(502);
    }

    private void assertNothingSaved(long caseId, long draftId, long version) {
        assertThat(jdbc.queryForObject("select count(*) from ops.str_drafts where case_id = ?", Long.class, caseId)).isZero();
        assertThat(requestStatus(draftId)).isEqualTo("REQUESTED");                        // FAILED 는 부른 쪽 몫
        assertThat(jdbc.queryForMap("select status, current_draft_id, version from ops.cases where id = ?", caseId))
                .containsEntry("status", "ESCALATED").containsEntry("current_draft_id", null).containsEntry("version", version);
    }

    /** 규약 5.2 모양의 7항목 응답. 단락마다 cites 2개, 근거 거래 3개. */
    private static ObjectNode response(long draftId, String generationId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", String.format("DRF-%06d", draftId))
                .put("generation_id", generationId);
        r.putObject("facts").put("excluded_self_transfer_count", 0).putObject("inbound").put("count", 3);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(104221).add(104233).add(104301);
        }
        r.putNull("verification");
        return r;
    }

    /** REQUESTED 요청 기록. snapshot 의 draft_id 는 AI 에 보낸 글자(DRF-%06d). */
    private long requested(long caseId) {
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", String.format("DRF-%06d", draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        return draftId;
    }

    /** 경보 → ESCALATED 케이스, 담당 = investigator (StrDraftRepositoryTest 와 같은 재료). */
    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2023-01-01 00:00', '2023-01-02 00:00', '2023-01-02 00:00', '{}', '2023-01-01', 'SCORED') returning id",
                Long.class, "ALT-20230101-" + String.format("%06d", 700_000 + n), batchRunId, ruleId, "ACC-S" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2023-" + String.format("%06d", 700_000 + n), alertId, "ACC-S" + n, investigator);
    }

    private long user(String loginId) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '조사역', 'INVESTIGATOR', 1, 1) returning id", Long.class, loginId);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private String requestStatus(long draftId) {
        return jdbc.queryForObject("select status from ops.str_draft_requests where draft_id = ?", String.class, draftId);
    }

    private String draftStatus(long draftId) {
        return jdbc.queryForObject("select status from ops.str_drafts where id = ?", String.class, draftId);
    }

    private static void login(long userId) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, "INVESTIGATOR");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }
}
