package com.team.aml.str.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team.aml.shared.domain.DisplayNumber;
import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.str.api.StrDraftDetailResponse;
import com.team.aml.str.application.GetStrDraft.Access;
import com.team.aml.str.domain.DraftNotFoundException;
import com.team.aml.str.domain.NarrativeHash;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRepository;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrSection;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GetStrDraft (계약서 S2) — 실제 PostgreSQL. 초안은 SaveStrDraft 로 만든다(SaveStrDraftDbTest 와 같은 재료).
 * 담당 조사역·승인자는 FULL, 비담당 조사역·ADMIN 은 META_ONLY. 둘 다 STR_VIEW 한 줄, 404 는 기록 없음.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class GetStrDraftDbTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT_KEY = "010_8000EBD30";

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

    @Autowired GetStrDraft getDraft;
    @Autowired SaveStrDraft saveDraft;
    @Autowired StrDraftRequestRepository requests;
    @Autowired StrDraftRepository drafts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper om;

    long investigator;
    long otherInvestigator;
    long approver;
    long admin;
    long batchRunId;
    long ruleId;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        investigator = user("get-inv" + n, "INVESTIGATOR");
        otherInvestigator = user("get-inv-other" + n, "INVESTIGATOR");
        approver = user("get-apr" + n, "APPROVER");
        admin = user("get-adm" + n, "ADMIN");
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2024-01-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 500 + n);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ── 정상 ──────────────────────────────────────────────────

    // 1
    @Test
    void 정상_담당_조사역은_FULL이고_case_version이_케이스_version과_같고_STR_VIEW_1행() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.access()).isEqualTo(Access.FULL);
        assertThat(r.draftNo()).isEqualTo(DisplayNumber.draftNo(draftId));
        assertThat(r.caseNo()).isEqualTo(caseNo(caseId));
        assertThat(r.caseStatus()).isEqualTo("DRAFTED");
        assertThat(r.caseVersion()).isEqualTo(caseVersion(caseId));
        assertThat(r.version()).isNotNull();
        assertThat(r.facts().path("subject").path("account_key").asText()).isEqualTo(ACCOUNT_KEY);   // 가리기는 api 몫
        assertThat(r.narrative()).hasSize(StrSection.V2.size());
        assertThat(r.currentRevisionNo()).isEqualTo(1);
        assertThat(r.latestVerification()).isNull();
        assertThat(strViewCount(draftId)).isEqualTo(1);
    }

    // 2
    @Test
    void 정상_승인자는_FULL() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(approver, "APPROVER");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.access()).isEqualTo(Access.FULL);
        assertThat(r.facts()).isNotNull();
        assertThat(r.narrative()).isNotEmpty();
        assertThat(strViewCount(draftId)).isEqualTo(1);
    }

    // 3
    @Test
    void 정상_비담당_조사역은_예외_없이_META_ONLY이고_본문이_null이며_STR_VIEW_1행() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(otherInvestigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertMetaOnly(r, draftId, caseId);
        assertThat(strViewCount(draftId)).isEqualTo(1);
    }

    // 4
    @Test
    void 정상_ADMIN은_META_ONLY이고_STR_VIEW_1행() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(admin, "ADMIN");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertMetaOnly(r, draftId, caseId);
        assertThat(strViewCount(draftId)).isEqualTo(1);
    }

    // 5
    @Test
    void 정상_응답_JSON에는_계좌_원문과_account_key가_없고_account_no와_account_ref가_있다() throws Exception {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        String json = om.writeValueAsString(StrDraftDetailResponse.from(getDraft.execute(DisplayNumber.draftNo(draftId))));

        assertThat(json).doesNotContain(ACCOUNT_KEY).doesNotContain("account_key")
                .contains("\"account_no\":\"010_****BD30\"")
                .contains("\"account_ref\":\"ACR-")
                .contains("\"from_account_no\":\"010_****BD30\"")       // 접두어가 붙은 키도
                .contains("\"evidence_ids\"")
                .contains("\"case_version\"")
                .contains("\"case_status\":\"DRAFTED\"");
        JsonNode narrative = om.readTree(json).path("narrative");
        assertThat(narrative.get(0).path("section").asText()).isEqualTo("SUBJECT");
        assertThat(narrative.get(0).path("label").asText()).isEqualTo("거래자");
        assertThat(narrative.get(6).path("label").asText()).isEqualTo("종합의견");
    }

    // 6
    @Test
    void 정상_META_ONLY_응답_JSON에도_본문_키가_null로_있다() throws Exception {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(admin, "ADMIN");

        JsonNode body = om.readTree(om.writeValueAsString(
                StrDraftDetailResponse.from(getDraft.execute(DisplayNumber.draftNo(draftId)))));

        assertThat(body.path("access").asText()).isEqualTo("META_ONLY");
        assertThat(body.path("case_no").asText()).isEqualTo(caseNo(caseId));
        assertThat(body.path("case_status").asText()).isEqualTo("DRAFTED");
        for (String key : new String[] {"version", "case_version", "facts", "narrative",
                "current_revision_no", "latest_verification", "generation_id", "generator_version", "suspicion_types",
                "suspicion_types_recommended", "suspicion_level_branch", "suspicion_level_hq", "references"}) {
            assertThat(body.has(key)).as(key).isTrue();
            assertThat(body.get(key).isNull()).as(key).isTrue();
        }
    }

    // 7
    @Test
    void 정상_검증이_2건이면_최신_것이_오고_revision_no가_들어_있다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        verification(draftId, "v-old-" + draftId, "2026-11-03T02:00:00Z");
        verification(draftId, "v-new-" + draftId, "2026-11-03T03:00:00Z");
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.latestVerification().path("verification_id").asText()).isEqualTo("v-new-" + draftId);
        assertThat(r.latestVerification().path("revision_no").asInt()).isEqualTo(1);
        assertThat(r.latestVerification().path("is_current").asBoolean()).isTrue();
    }

    // 7-2
    @Test
    void 정상_다른_본문을_검사한_검증이면_is_current가_false() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        verification(draftId, "v-" + draftId, "2026-11-03T02:00:00Z", 1, "b".repeat(64));
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.latestVerification().path("is_current").asBoolean()).isFalse();
    }

    // 7-2-1
    @Test
    void 정상_수정본_번호가_달라도_본문_해시가_같으면_is_current가_true() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        // 고쳤다가 되돌린 경우 — 검증한 수정본(2번)과 지금 수정본(1번)은 다르지만 문장이 같다
        verification(draftId, "v-" + draftId, "2026-11-03T02:00:00Z", 2, currentHash(draftId));
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.currentRevisionNo()).isEqualTo(1);
        assertThat(r.latestVerification().path("is_current").asBoolean()).isTrue();
    }

    // 7-3
    @Test
    void 정상_지금_본문_해시의_최신_검증에_narrative_refs가_있으면_근거는_그_값이고_문장과_수정본은_그대로() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        verificationWithRefs(draftId, "v-refs-" + draftId, currentHash(draftId),
                "[{\"section\":\"METHOD\",\"cites\":[\"counterparties.count\"],\"evidence_ids\":[104233]}]");
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        var method = r.narrative().stream().filter(n -> n.section() == StrSection.METHOD).findFirst().orElseThrow();
        assertThat(method.text()).isEqualTo("AI METHOD 문장");
        assertThat(method.cites()).containsExactly("counterparties.count");
        assertThat(method.evidenceIds()).containsExactly(104233L);
        var date = r.narrative().stream().filter(n -> n.section() == StrSection.DATE).findFirst().orElseThrow();
        assertThat(date.cites()).containsExactly("period", "inbound.count");          // refs 에 없는 단락은 수정본 값
        assertThat(jdbc.queryForObject("select narrative_after -> 4 -> 'cites' ->> 0 from ops.str_revisions "
                + "where draft_id = ?", String.class, draftId)).isEqualTo("period");   // 수정본은 그대로(저장 안 함)
    }

    // 7-4
    @Test
    void 정상_다른_본문을_검사한_검증의_narrative_refs는_쓰지_않는다() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        verificationWithRefs(draftId, "v-old-refs-" + draftId, "b".repeat(64),
                "[{\"section\":\"METHOD\",\"cites\":[\"counterparties.count\"],\"evidence_ids\":[104233]}]");
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        var method = r.narrative().stream().filter(n -> n.section() == StrSection.METHOD).findFirst().orElseThrow();
        assertThat(method.cites()).containsExactly("period", "inbound.count");
    }

    // ── 실패 ──────────────────────────────────────────────────

    // 8
    @Test
    void 실패_없는_번호와_형식이_틀린_번호는_DraftNotFoundException이고_열람_기록이_없다() {
        login(investigator, "INVESTIGATOR");

        assertThatThrownBy(() -> getDraft.execute("DRF-999999")).isInstanceOf(DraftNotFoundException.class);
        assertThatThrownBy(() -> getDraft.execute("DRF-abc")).isInstanceOf(DraftNotFoundException.class);

        assertThat(strViewCount(999_999L)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ops.access_logs where action='STR_VIEW' and user_id=?",
                Long.class, investigator)).isZero();
    }

    // 9
    @Test
    void 정상_FULL에는_생성_정보와_의심_유형과_정도가_오고_승인_요청_전이면_빈_값() {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        login(investigator, "INVESTIGATOR");

        GetStrDraft.Result r = getDraft.execute(DisplayNumber.draftNo(draftId));

        assertThat(r.generationId()).isEqualTo("g-" + draftId);
        assertThat(r.generatorVersion()).isEqualTo("narrative_v2");          // generation_meta.prompt_version
        assertThat(r.suspicionTypes()).isEmpty();                            // S6 전 — null 아니라 []
        assertThat(r.suspicionLevelBranch()).isNull();
        assertThat(r.suspicionLevelHq()).isNull();
        assertThat(r.suspicionTypesRecommended()).isEmpty();                 // 룰 → 유형 표 전까지 항상 [] (rules.md 미결 4)
        assertThat(r.references()).isEmpty();                                // RAG 응답 칸 확인 전까지 항상 []
    }

    // 10
    @Test
    void 정상_승인_요청과_승인_뒤_다시_열면_지난_의심_유형과_정도가_채워져_오고_JSON_키는_snake_case() throws Exception {
        long caseId = escalatedCase();
        long draftId = savedDraft(caseId);
        jdbc.update("update ops.str_drafts set suspicion_types = '{VI-1,VI-4}', suspicion_level_branch = 3, "
                + "suspicion_level_hq = 4 where id = ?", draftId);   // S6·P2 가 남긴 값 (저장 경로는 각 유스케이스 테스트가 본다)
        login(approver, "APPROVER");

        JsonNode body = om.readTree(om.writeValueAsString(
                StrDraftDetailResponse.from(getDraft.execute(DisplayNumber.draftNo(draftId)))));

        assertThat(body.path("suspicion_types").toString()).isEqualTo("[\"VI-1\",\"VI-4\"]");
        assertThat(body.path("suspicion_level_branch").asInt()).isEqualTo(3);
        assertThat(body.path("suspicion_level_hq").asInt()).isEqualTo(4);
        assertThat(body.path("suspicion_types_recommended").isArray()).isTrue();
        assertThat(body.path("references").isArray()).isTrue();
        assertThat(body.path("generation_id").asText()).isEqualTo("g-" + draftId);
        assertThat(body.path("generator_version").asText()).isEqualTo("narrative_v2");
    }

    // ---- 도우미 ----

    private void assertMetaOnly(GetStrDraft.Result r, long draftId, long caseId) {
        assertThat(r.access()).isEqualTo(Access.META_ONLY);
        assertThat(r.draftNo()).isEqualTo(DisplayNumber.draftNo(draftId));
        assertThat(r.status()).isNotNull();
        assertThat(r.producedAt()).isNotNull();
        assertThat(r.facts()).isNull();
        assertThat(r.narrative()).isNull();
        assertThat(r.latestVerification()).isNull();
        assertThat(r.caseNo()).isEqualTo(caseNo(caseId));          // 상태·날짜는 준다 (계약서 S2 제한 응답)
        assertThat(r.caseStatus()).isEqualTo("DRAFTED");
        assertThat(r.version()).isNull();
        assertThat(r.caseVersion()).isNull();
        assertThat(r.currentRevisionNo()).isNull();
        assertThat(r.generationId()).isNull();
        assertThat(r.suspicionTypes()).isNull();
        assertThat(r.suspicionTypesRecommended()).isNull();
        assertThat(r.suspicionLevelBranch()).isNull();
        assertThat(r.references()).isNull();
    }

    private long strViewCount(long draftId) {
        return jdbc.queryForObject("select count(*) from ops.access_logs where action='STR_VIEW' and target_id=?",
                Long.class, draftId);
    }

    /** 담당 조사역으로 SaveStrDraft — 케이스 DRAFTED, 초안 ACTIVE, 수정본 1번. 끝나면 로그아웃. */
    private long savedDraft(long caseId) {
        long draftId = requests.nextDraftId();
        JsonNode snapshot = JSON.createObjectNode().put("draft_id", DisplayNumber.draftNo(draftId));
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot)));
        login(investigator, "INVESTIGATOR");
        saveDraft.execute(caseId, draftId, caseVersion(caseId), response(draftId));
        SecurityContextHolder.clearContext();
        return draftId;
    }

    /** 규약 5.2 모양의 7항목 응답. facts 에 계좌 원문(subject.account_key, inbound.top[].from_account_key). */
    private static ObjectNode response(long draftId) {
        ObjectNode r = JSON.createObjectNode()
                .put("produced_at", "2026-11-03T01:10:00Z")
                .put("draft_id", DisplayNumber.draftNo(draftId))
                .put("generation_id", "g-" + draftId);
        ObjectNode facts = r.putObject("facts");
        facts.putObject("subject").put("account_key", ACCOUNT_KEY);
        ObjectNode inbound = facts.putObject("inbound").put("count", 3);
        inbound.putArray("top").addObject().put("from_account_key", ACCOUNT_KEY).put("amount", 1000);
        r.putObject("generation_meta").put("prompt_version", "narrative_v2");
        var narrative = r.putArray("narrative");
        for (StrSection s : StrSection.V2) {
            ObjectNode n = narrative.addObject().put("section", s.name()).put("text", "AI " + s + " 문장");
            n.putArray("cites").add("period").add("inbound.count");
            n.putArray("evidence_ids").add(104221).add(104233);
        }
        r.putNull("verification");
        return r;
    }

    private void verification(long draftId, String verificationId, String producedAt) {
        verification(draftId, verificationId, producedAt, 1, currentHash(draftId));
    }

    private void verification(long draftId, String verificationId, String producedAt, int revisionNo, String hash) {
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, ?, ?, 'PASSED', 'verify_v1', ?::timestamptz, ?::jsonb, 1)",
                verificationId, draftId, "g-" + draftId, revisionNo, hash, producedAt,
                "{\"verification_id\":\"" + verificationId + "\",\"status\":\"PASSED\"}");
    }

    /** 지금 수정본 본문의 NarrativeHash — CurrentNarrative 가 찾는 narrative_sha256. */
    private String currentHash(long draftId) {
        return tx.execute(s -> NarrativeHash.of(drafts.findById(draftId).orElseThrow().currentBody()));
    }

    private void verificationWithRefs(long draftId, String verificationId, String hash, String refsJson) {
        jdbc.update("insert into ops.verification_results(verification_id, draft_id, generation_id, revision_no, "
                        + "narrative_sha256, status, verifier_version, produced_at, payload, created_by) "
                        + "values (?, ?, ?, 1, ?, 'PASSED', 'verify_v1', '2026-11-03T04:00:00Z', ?::jsonb, 1)",
                verificationId, draftId, "g-" + draftId, hash,
                "{\"verification_id\":\"" + verificationId + "\",\"status\":\"PASSED\",\"narrative_refs\":" + refsJson + "}");
    }

    /** 경보 → ESCALATED 케이스, 담당 = investigator. */
    private long escalatedCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                + "'2024-01-01 00:00', '2024-01-02 00:00', '2024-01-02 00:00', '{}', '2024-01-01', 'SCORED') returning id",
                Long.class, "ALT-20240101-" + String.format("%06d", 800_000 + n), batchRunId, ruleId, "ACC-G" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2024-" + String.format("%06d", 800_000 + n), alertId, "ACC-G" + n, investigator);
    }

    private long user(String loginId, String role) {
        return jdbc.queryForObject("insert into ops.users(login_id, display_name, role, created_by, updated_by) "
                + "values (?, '사용자', ?, 1, 1) returning id", Long.class, loginId, role);
    }

    private long caseVersion(long caseId) {
        return jdbc.queryForObject("select version from ops.cases where id = ?", Long.class, caseId);
    }

    private String caseNo(long caseId) {
        return jdbc.queryForObject("select case_no from ops.cases where id = ?", String.class, caseId);
    }

    private static void login(long userId, String role) {
        AppUserDetails me = new AppUserDetails(userId, "u" + userId, "u" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities()));
    }
}
