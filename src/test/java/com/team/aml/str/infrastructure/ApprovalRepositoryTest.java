package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.cases.domain.RejectionReasonCode;
import com.team.aml.str.domain.Approval;
import com.team.aml.str.domain.ApprovalDecision;
import com.team.aml.str.domain.ApprovalRepository;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.NarrativeSection;
import com.team.aml.str.domain.StrDraft;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
import com.team.aml.str.domain.VerificationResult;
import com.team.aml.str.domain.VerificationResultRepository;
import com.team.aml.str.domain.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approval·VerificationResult 조회 매핑을 실제 PostgreSQL(V001~V011)에서 확인한다.
 * 저장 → 새 트랜잭션에서 다시 읽기. 로그인이 없어 created_by 는 SYSTEM(1). 앱 계정은 app_backend — V009 revoke 가 걸린다.
 * 기준: V009 approvals(복합 FK·결정별 CHECK·초안당 승인 한 번), FR-APR-03(같은 본문 검증 중 최신), 게이트 4번(반려 당시 해시).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ApprovalRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Instant PRODUCED_AT = Instant.parse("2026-11-03T01:10:00Z");

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

    @Autowired StrDraftRepository drafts;
    @Autowired StrDraftRequestRepository requests;
    @Autowired VerificationResultRepository verifications;
    @Autowired ApprovalRepository approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long investigator;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '조사역', 'INVESTIGATOR', 1, 1) returning id", Long.class, "apr-inv" + n);
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2022-09-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 300 + n);
    }

    // 1
    @Test
    void 정상_승인_기록을_저장하면_수정본_검증_해시가_DB_FK와_CHECK를_통과한다() {
        long draftId = savedDraft();
        long verificationRowId = tx.execute(s -> verifications.save(verification(draftId, "v-ok-" + draftId,
                "2026-11-03T02:00:00Z", VerificationStatus.PASSED)).getId());

        long approvalId = tx.execute(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            VerificationResult v = verifications.findSameBody(draftId, NarrativeHash.of(draft.currentBody())).get(0);
            draft.recordHqSuspicion(4);
            return approvals.save(Approval.approved(draft, v)).getId();
        });

        assertThat(jdbc.queryForObject("select decision || ',' || verification_result_id || ',' || created_by "
                + "from ops.approvals where id = ?", String.class, approvalId))
                .isEqualTo("APPROVED," + verificationRowId + ",1");
        assertThat(jdbc.queryForObject("select revision_id = (select current_revision_id from ops.str_drafts "
                + "where id = ?) from ops.approvals where id = ?", Boolean.class, draftId, approvalId)).isTrue();
        assertThat(jdbc.queryForObject("select suspicion_level_hq from ops.str_drafts where id = ?",
                Integer.class, draftId)).isEqualTo(4);
    }

    // 2
    @Test
    void 정상_가장_최근_반려를_찾고_반려_당시_해시가_남는다() {
        long draftId = savedDraft();
        String firstHash = tx.execute(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            approvals.save(Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, "첫 보완 요청"));
            return NarrativeHash.of(draft.currentBody());
        });
        tx.executeWithoutResult(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            draft.revise(Map.of(StrSection.HOW, "보완한 문장"), investigator);
            drafts.save(draft);
        });
        tx.executeWithoutResult(s -> {
            StrDraft draft = drafts.findById(draftId).orElseThrow();
            approvals.save(Approval.rejected(draft, RejectionReasonCode.NEED_REVISION, "두 번째 보완 요청"));
        });

        Optional<Approval> latest = tx.execute(s -> approvals.findLatestRejection(draftId));

        assertThat(latest).get().satisfies(a -> {
            assertThat(a.getDecision()).isEqualTo(ApprovalDecision.REJECTED);
            assertThat(a.getComment()).isEqualTo("두 번째 보완 요청");
            assertThat(a.getReasonCode()).isEqualTo(RejectionReasonCode.NEED_REVISION);
            assertThat(a.getNarrativeSha256()).isNotEqualTo(firstHash);
            assertThat(a.isNeedRevision()).isTrue();
        });
        Optional<Approval> latestRejection =
                tx.execute(s -> approvals.findLatestRejection(savedDraft()));

        assertThat(latestRejection).isEmpty(); // 반려 없는 초안
    }

    // 3
    @Test
    void 정상_같은_본문_검증만_돌려주고_다른_본문_검증은_빠진다() {
        long draftId = savedDraft();
        String hash = tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
        tx.executeWithoutResult(s -> {
            verifications.save(verification(draftId, "v-a-" + draftId, "2026-11-03T02:00:00Z", VerificationStatus.PASSED));
            verifications.save(verification(draftId, "v-b-" + draftId, "2026-11-03T03:00:00Z", VerificationStatus.LLM_FAILED));
            verifications.save(VerificationResult.record(draftId, "v-c-" + draftId, "g-" + draftId, 1, "b".repeat(64),
                    VerificationStatus.PASSED, "verify_v1", Instant.parse("2026-11-03T04:00:00Z"),
                    new ObjectMapper().createObjectNode()));
        });

        List<VerificationResult> same = tx.execute(s -> verifications.findSameBody(draftId, hash));

        assertThat(same).extracting(VerificationResult::getVerificationId)
                .containsExactlyInAnyOrder("v-a-" + draftId, "v-b-" + draftId);
        assertThat(same).allSatisfy(v -> assertThat(v.getProducedAt()).isNotNull());
    }

    // ---- 도우미 ----

    /** 끝난 요청 → 초안(수정본 1번) 저장. current_revision_id 까지 맞춰진 상태. */
    private long savedDraft() {
        long caseId = newCase();
        long draftId = finishedRequest(caseId);
        return tx.execute(s -> drafts.save(newDraft(draftId, caseId)).getId());
    }

    /** 이 초안의 지금 본문(수정본 1번)을 검사한 검증. */
    private VerificationResult verification(long draftId, String verificationId, String producedAt,
                                            VerificationStatus status) {
        String hash = NarrativeHash.of(aiNarrative());
        return VerificationResult.record(draftId, verificationId, "g-" + draftId, 1, hash, status, "verify_v1",
                Instant.parse(producedAt), new ObjectMapper().createObjectNode().put("status", status.name()));
    }

    /** 경보 → 케이스. 초안을 붙이려면 ESCALATED + 담당자여야 V006 CHECK를 통과한다. */
    private long newCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2022-09-01 00:00', '2022-09-02 00:00', '2022-09-02 00:00', '{}', '2022-09-01', 'SCORED') returning id",
                Long.class, "ALT-20220901-" + String.format("%06d", 600_000 + n), batchRunId, ruleId, "ACC-AP" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2022-" + String.format("%06d", 600_000 + n), alertId, "ACC-AP" + n, investigator);
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
                .map(s -> new NarrativeSection(s, "AI " + s + " 문장", List.of("period", "inbound.count"),
                        List.of(5_120_000L + s.ordinal(), 5_121_000L)))
                .toList();
    }

}
