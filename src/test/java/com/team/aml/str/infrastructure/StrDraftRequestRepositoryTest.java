package com.team.aml.str.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.str.domain.StrDraftRequest;
import com.team.aml.str.domain.StrDraftRequestRepository;
import com.team.aml.str.domain.StrDraftRequestStatus;
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

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StrDraftRequest 매핑을 실제 PostgreSQL(V001~V009)에서 확인한다.
 * 저장 → 새 트랜잭션에서 다시 읽기 → 값 비교. 로그인이 없어 감사 칸은 SYSTEM(1).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class StrDraftRequestRepositoryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml").withUsername("postgres").withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

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
                + "values (?, '조사역', 'INVESTIGATOR', 1, 1) returning id", Long.class, "req-inv" + n);
        batchRunId = jdbc.queryForObject("insert into ops.batch_runs(batch_type, batch_date, status, triggered_by) "
                + "values ('RULE_SCAN', date '2022-09-01' + ?, 'SUCCEEDED', 1) returning id", Long.class, n);
        ruleId = jdbc.queryForObject("insert into ops.rules(rule_id, version, name, params, created_by, updated_by) "
                + "values ('R02_FAN_IN_24H', ?, 'fan-in', '{}', 1, 1) returning id", Long.class, 100 + n);
    }

    // 1
    @Test
    void 요청_기록은_시퀀스_번호로_저장되고_진행_중인_것을_케이스로_찾는다() {
        long caseId = newCase();
        long first = requests.nextDraftId();
        assertThat(requests.nextDraftId()).isEqualTo(first + 1);              // ops.str_draft_id_seq

        long draftId = requests.nextDraftId();
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot(draftId))));

        StrDraftRequest found = tx.execute(s ->
                requests.findByCaseIdAndStatus(caseId, StrDraftRequestStatus.REQUESTED).orElseThrow());
        assertThat(found.getId()).isEqualTo(draftId);
        assertThat(found.isNew()).isFalse();
        assertThat(found.getRequestSnapshot()).isEqualTo(snapshot(draftId));   // jsonb 왕복
        assertThat(found.getFinishedAt()).isNull();
        assertThat(found.getCreatedBy()).isEqualTo(1L);                       // 로그인 없음 → SYSTEM
    }

    // 2
    @Test
    void 성공_처리하면_끝난_시각이_남고_진행_중_목록에서_빠진다() {
        long caseId = newCase();
        long draftId = requests.nextDraftId();
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(draftId, caseId, snapshot(draftId))));

        Instant at = Instant.parse("2026-11-04T05:00:00Z");
        tx.executeWithoutResult(s -> requests.findById(draftId).orElseThrow().succeed(at));   // 변경 감지로 UPDATE

        Optional<StrDraftRequest> stillRequested =
                tx.execute(s -> requests.findByCaseIdAndStatus(caseId, StrDraftRequestStatus.REQUESTED));
        assertThat(stillRequested).isEmpty();
        StrDraftRequest done = tx.execute(s -> requests.findById(draftId).orElseThrow());
        assertThat(done.getStatus()).isEqualTo(StrDraftRequestStatus.SUCCEEDED);
        assertThat(done.getFinishedAt()).isEqualTo(at);
    }

    // 3
    @Test
    void 케이스마다_진행_중인_요청은_하나라_두_번째_요청은_저장되지_않는다() {
        long caseId = newCase();
        long firstId = requests.nextDraftId();
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(firstId, caseId, snapshot(firstId))));

        long secondId = requests.nextDraftId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                requests.save(StrDraftRequest.request(secondId, caseId, snapshot(secondId)))))
                .isInstanceOf(DataIntegrityViolationException.class);       // V008 str_draft_requests_one_requested_uq

        // 첫 요청이 끝나면 새 요청은 된다 (다시 생성)
        tx.executeWithoutResult(s -> requests.findById(firstId).orElseThrow().fail(Instant.parse("2026-11-04T05:00:00Z")));
        long thirdId = requests.nextDraftId();
        tx.executeWithoutResult(s -> requests.save(StrDraftRequest.request(thirdId, caseId, snapshot(thirdId))));
        Optional<Long> active = tx.execute(s -> requests.findByCaseIdAndStatus(caseId, StrDraftRequestStatus.REQUESTED)
                .map(StrDraftRequest::getId));
        assertThat(active).contains(thirdId);
    }

    // ---- 도우미 ----

    /** 경보 → 케이스(ESCALATED + 담당자, V006 CHECK). 요청 기록은 case_id FK 만 본다. */
    private long newCase() {
        int n = SEQ.incrementAndGet();
        long alertId = jdbc.queryForObject("insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, "
                        + "window_start, window_end, reference_ts, rule_facts, batch_date, score_status) values (?, ?, ?, ?, "
                        + "'2022-09-01 00:00', '2022-09-02 00:00', '2022-09-02 00:00', '{}', '2022-09-01', 'SCORED') returning id",
                Long.class, "ALT-20220901-" + String.format("%06d", 600_000 + n), batchRunId, ruleId, "ACC-Q" + n);
        return jdbc.queryForObject("insert into ops.cases(case_no, alert_id, account_key, status, assignee_id, "
                        + "created_by, updated_by) values (?, ?, ?, 'ESCALATED', ?, 1, 1) returning id",
                Long.class, "CASE-2022-" + String.format("%06d", 600_000 + n), alertId, "ACC-Q" + n, investigator);
    }

    private static JsonNode snapshot(long draftId) {
        return new ObjectMapper().createObjectNode().put("draft_id", String.format("DRF-%06d", draftId));
    }
}
