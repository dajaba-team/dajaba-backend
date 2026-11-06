package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrDraftStatus;
import com.team.aml.str.domain.StrRevision;
import com.team.aml.str.domain.StrSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StrDraft·StrRevision 매핑을 실제 PostgreSQL(V001~V009)에서 확인한다.
 * 저장 → 영속성 컨텍스트를 비운 새 트랜잭션에서 다시 읽기 → 값 비교. 로그인이 없어 감사 칸은 SYSTEM(1).
 * 앱은 운영과 같은 app_backend 계정으로 붙는다 — V008 의 revoke(수정본 UPDATE·초안 DELETE 금지)가 테스트에도 걸린다 (#53 리뷰).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class StrDraftRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Long INVESTIGATOR_REVISER = 1L;      // 수정자. users 에 있는 번호여야 해서 SYSTEM 을 쓴다
    private static final Instant PRODUCED_AT = Instant.parse("2026-11-03T01:10:00Z");
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    // 앱(JPA·JdbcTemplate)은 app_backend, Flyway(DDL)는 app_migrate — application.yml 과 같은 계정 구성
    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_backend");
        registry.add("spring.datasource.password", () -> "backend");
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired StrDraftRepository drafts;
    @Autowired StrDraftRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        // 케이스를 만들 재료 (StrMigrationIsolatedTest 와 같은 값). 테스트마다 새 login_id·batch_date·rule 버전.
        // batch_date: 같은 날짜의 SUCCEEDED 배치는 하나만 허용된다 (V004 batch_runs_succeeded_uq)
        int n = SEQ.incrementAndGet();
        investigator = jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '조사역', 'INVESTIGATOR', 1, 1) returning id", Long.class, "repo-inv" + n);
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2022-09-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 100 + n);
    }

    // 1
    @Test
    void 초안을_저장하고_다시_읽으면_본문_facts_수정본_1번이_그대로다() {
        long caseId = newCase();
        long draftId = tx.execute(s -> {
            StrDraft draft = drafts.save(newDraft(finishedRequest(caseId), caseId));
            return draft.getId();
        });

        tx.executeWithoutResult(s -> {
            StrDraft loaded = drafts.findById(draftId).orElseThrow();
            assertThat(loaded.isNew()).isFalse();
            assertThat(loaded.getCaseId()).isEqualTo(caseId);
            assertThat(loaded.getStatus()).isEqualTo(StrDraftStatus.ACTIVE);
            assertThat(loaded.getGeneratorVersion()).isEqualTo("narrative_v1");
            assertThat(loaded.getProducedAt()).isEqualTo(PRODUCED_AT);
            assertThat(loaded.getFacts()).isEqualTo(facts());              // DB generated column = payload -> 'facts'
            assertThat(loaded.getPayload().path("generation_id").asText()).isEqualTo("g-" + draftId);
            assertThat(loaded.currentBody()).isEqualTo(aiNarrative());     // jsonb 왕복 — 순서·근거 번호까지
            assertThat(loaded.getVersion()).isZero();

            StrRevision first = loaded.getCurrentRevision();
            assertThat(first.getRevisionNo()).isEqualTo(1);
            assertThat(first.getBefore()).isEmpty();
            assertThat(first.getEditedSections()).isEmpty();
            assertThat(first.getRevisedBy()).isEqualTo(1L);                // SYSTEM
            assertThat(first.getCreatedAt()).isNotNull();
            assertThat(loaded.getCurrentRevisionId()).isEqualTo(first.getId());
        });

        // DB 에서 직접: 현재 수정본 포인터, jsonb 키 이름(snake_case)
        assertThat(jdbc.queryForObject("select current_revision_id from ops.str_drafts where id=?", Long.class, draftId))
                .isNotNull();
        assertThat(jdbc.queryForObject("select narrative_after -> 0 ->> 'section' from ops.str_revisions where draft_id=?",
                String.class, draftId)).isEqualTo("SUMMARY");
        assertThat(jdbc.queryForObject("select jsonb_array_length(narrative_after -> 0 -> 'evidence_ids') "
                + "from ops.str_revisions where draft_id=?", Integer.class, draftId)).isEqualTo(2);
    }

    // 2
    @Test
    void 수정하면_수정본_2번이_추가되고_현재_수정본과_버전이_바뀐다() {
        long caseId = newCase();
        long draftId = tx.execute(s -> drafts.save(newDraft(finishedRequest(caseId), caseId)).getId());

        tx.executeWithoutResult(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            draft.revise(Map.of(StrSection.HOW, "고친 HOW"), INVESTIGATOR_REVISER);
            drafts.save(draft);
        });

        tx.executeWithoutResult(s -> {
            StrDraft loaded = drafts.findById(draftId).orElseThrow();
            assertThat(loaded.getRevisions()).extracting(StrRevision::getRevisionNo).containsExactly(1, 2);
            StrRevision second = loaded.getCurrentRevision();
            assertThat(second.getRevisionNo()).isEqualTo(2);
            assertThat(second.getEditedSections()).containsExactly(StrSection.HOW);
            assertThat(second.getBefore()).isEqualTo(aiNarrative());
            assertThat(loaded.currentBody()).filteredOn(n -> n.section() == StrSection.HOW)
                    .extracting(NarrativeSection::text).containsExactly("고친 HOW");
            assertThat(loaded.getCurrentRevisionId()).isEqualTo(second.getId());
            assertThat(loaded.getVersion()).isEqualTo(1L);   // 수정 한 번 = 버전 한 번 (current_revision_id 는 버전을 안 올림)
        });
    }

    // 3
    @Test
    void 같은_draft_id로_초안을_또_만들면_저장이_실패한다() {
        long caseId = newCase();
        long draftId = finishedRequest(caseId);
        tx.executeWithoutResult(s -> drafts.save(newDraft(draftId, caseId)));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> drafts.save(newDraft(draftId, caseId))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 4
    @Test
    void 사용_중인_초안은_케이스로_찾고_대체하면_안_나온다() {
        long caseId = newCase();
        long draftId = tx.execute(s -> drafts.save(newDraft(finishedRequest(caseId), caseId)).getId());

        // tx.execute의 반환 타입 T는 assertThat 오버로드로는 추론되지 않으므로 타입이 있는 변수에 먼저 담는다
        Optional<Long> activeId = tx.execute(s -> drafts.findActiveByCaseId(caseId).map(StrDraft::getId));
        assertThat(activeId).contains(draftId);

        tx.executeWithoutResult(s -> drafts.findById(draftId).orElseThrow().supersede());
        Optional<StrDraft> afterSupersede = tx.execute(s -> drafts.findActiveByCaseId(caseId));
        assertThat(afterSupersede).isEmpty();
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id=?", String.class, draftId))
                .isEqualTo("SUPERSEDED");
    }

    // 5
    @Test
    void 재생성하면_한_트랜잭션에서_예전_초안은_SUPERSEDED_새_초안이_사용_중이_된다() {
        long caseId = newCase();
        long oldId = tx.execute(s -> drafts.save(newDraft(finishedRequest(caseId), caseId)).getId());
        long newId = finishedRequest(caseId);              // 재생성 요청도 끝난 상태로

        // SaveStrDraft 의 재생성 흐름: 예전 초안 대체 → 새 초안 저장, 한 트랜잭션
        tx.executeWithoutResult(s -> {
            drafts.findActiveByCaseId(caseId).orElseThrow().supersede();
            drafts.save(newDraft(newId, caseId));          // save 의 flush 가 없으면 여기서 str_drafts_one_active_uq 위반
        });

        Optional<Long> activeId = tx.execute(s -> drafts.findActiveByCaseId(caseId).map(StrDraft::getId));
        assertThat(activeId).contains(newId);
        assertThat(jdbc.queryForObject("select status from ops.str_drafts where id=?", String.class, oldId))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select count(*) from ops.str_drafts where case_id=? and status='ACTIVE'",
                Integer.class, caseId)).isEqualTo(1);
    }

    // 6
    @Test
    void 앱_계정은_수정본을_고치거나_지울_수_없고_초안도_지울_수_없다() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("app_backend");
        long caseId = newCase();
        long draftId = tx.execute(s -> drafts.save(newDraft(finishedRequest(caseId), caseId)).getId());

        assertThat(sqlState(catchThrowable(() -> jdbc.update(
                "update ops.str_revisions set narrative_after = '[]' where draft_id=?", draftId))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(catchThrowable(() -> jdbc.update(
                "delete from ops.str_revisions where draft_id=?", draftId))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(sqlState(catchThrowable(() -> jdbc.update(
                "delete from ops.str_drafts where id=?", draftId))))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // ---- 도우미 ----

    /** 경보 → 케이스. 초안을 붙이려면 ESCALATED + 담당자여야 V006 CHECK를 통과한다. */
    private long newCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2022-09-01 00:00', '2022-09-02 00:00', '2022-09-02 00:00', '{}', '2022-09-01', 'SCORED') returning id",
                Long.class, "ALT-20220901-" + String.format("%06d", 500_000 + n), batchRunId, ruleId, "ACC-R" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2022-" + String.format("%06d", 500_000 + n), alertId, "ACC-R" + n, investigator);
    }

    /** 끝난(SUCCEEDED) 요청 기록 — str_drafts 복합 FK 가 이 행을 가리킨다. */
    private long finishedRequest(long caseId) {
        long draftId = requests.nextDraftId();
        tx.executeWithoutResult(s -> {
            StrDraftRequest request = StrDraftRequest.request(draftId, caseId, snapshot(draftId));
            request.succeed(Instant.parse("2026-11-03T01:10:05Z"));
            requests.save(request);
        });
        return draftId;
    }

    private static StrDraft newDraft(long draftId, long caseId) {
        ObjectNode payload = new ObjectMapper().createObjectNode().put("generation_id", "g-" + draftId);
        payload.set("facts", facts());
        return StrDraft.create(draftId, caseId, "g-" + draftId, "narrative_v1", PRODUCED_AT, payload, aiNarrative());
    }

    private static JsonNode facts() {
        ObjectNode facts = new ObjectMapper().createObjectNode().put("excluded_self_transfer_count", 0);
        facts.putObject("inbound").put("count", 47);
        return facts;
    }

    private static JsonNode snapshot(long draftId) {
        return new ObjectMapper().createObjectNode().put("draft_id", String.format("DRF-%06d", draftId));
    }

    private static List<NarrativeSection> aiNarrative() {
        return StrSection.V1.stream()
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of(5_120_000L + s.ordinal(), 5_121_000L)))
                .toList();
    }

    /** 예외 사슬에서 SQLSTATE 를 꺼낸다. 스프링이 42501 을 어떤 예외로 바꾸든 상관없이 DB 코드로 확인. */
    private static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException e) {
                return e.getSQLState();
            }
        }
        return null;
    }
}
