package com.team.aml.detection.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.DlRank;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.domain.DlRankingRepository;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.DlFallbackReason;
import com.team.aml.shared.ai.AiDtos.DlRankedAlert;
import com.team.aml.shared.ai.AiDtos.DlRankingResponse;
import com.team.aml.shared.ai.MockAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ML+DL 조사 순위 대조 조회 (SCRUM-376) — 실제 PostgreSQL. AI 는 @MockBean 으로 테스트마다 응답을 정한다.
 * 경보는 지울 수 없어 테스트마다 날짜를 새로 쓴다(경보 번호 `ALT-{날짜}-…` 도 날짜별로 따로).
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class GetDlRankingDbTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger DAY = new AtomicInteger();
    private static final String MODEL = "xgb-hi-medium-r04-event-c2-31-v1";
    private static final String SHA = "a".repeat(64);

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

    @MockBean
    AiClient aiClient;

    @Autowired
    GetDlRanking getDlRanking;

    @Autowired
    DlRankingRepository repository;

    @Autowired
    ObjectMapper om;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    // ---- 정상 ----

    @Test
    void 정상_세_이유가_섞인_응답이_DB와_맞으면_Available_순위는_응답_순서대로() {
        Day d = newDay();
        String fanIn = d.rule("R02_FAN_IN");
        String fanOut = d.rule("R01_FAN_OUT");
        long a1 = d.alert(1, fanIn);
        long a2 = d.alert(2, fanOut);
        long a3 = d.alert(3, fanIn);
        long a4 = d.alert(4, fanIn);
        d.score(a1, "0.92", MODEL);
        d.score(a2, "0.74", MODEL);
        d.score(a4, "0.31", MODEL);
        d.failed(a3, "TRANSACTION_NOT_FOUND");   // 점수 행 없음 → 응답 ML_UNSCORED, AI 대로 DL 근거가 다 있으면 dl_score 도 있음
        respond(d, List.of(
                linked(d.no(1), fanIn, 0.92, 0.81, 1, 1),
                incomplete(d.no(2), fanOut, 0.74, 2),
                linked(d.no(4), fanIn, 0.31, 0.12, 3, 3),
                unscored(d.no(3), fanIn, 0.55)));

        DlRankingResult result = getDlRanking.execute(d.date);

        assertThat(result).isInstanceOf(Available.class);
        Available ok = (Available) result;
        assertThat(ok.scanDate()).isEqualTo(d.date);
        assertThat(ok.snapshotSha256()).isEqualTo(SHA);
        assertThat(ok.mlModel()).isEqualTo(MODEL);
        assertThat(ok.dlModel()).isEqualTo("dl-test");
        assertThat(ok.ranks().keySet()).containsExactly(d.no(1), d.no(2), d.no(4), d.no(3));
        assertThat(ok.ranks().get(d.no(1))).isEqualTo(new DlRank(1, 1, null, 0.81));
        assertThat(ok.ranks().get(d.no(2))).isEqualTo(new DlRank(2, 2, DlFallbackReason.INCOMPLETE_DL_EVIDENCE, null));
        assertThat(ok.ranks().get(d.no(3))).isEqualTo(new DlRank(null, null, DlFallbackReason.ML_UNSCORED, 0.55));
    }

    @Test
    void 경계_점수_차가_1e_7이면_통과() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.9200001", MODEL);
        respond(d, List.of(linked(d.no(1), r, 0.92, 0.5, 1, 1)));

        assertThat(getDlRanking.execute(d.date)).isInstanceOf(Available.class);
    }

    @Test
    void 경계_점수_차가_정확히_1e_6이면_통과_조금이라도_넘으면_SCORE_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.500001", MODEL);   // double 뺄셈이면 1.0000000000287557e-6 로 넘어감
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));
        assertThat(getDlRanking.execute(d.date)).isInstanceOf(Available.class);

        Day e = newDay();
        String r2 = e.rule("R02_FAN_IN");
        e.score(e.alert(1, r2), "0.5000011", MODEL);
        respond(e, List.of(linked(e.no(1), r2, 0.5, 0.5, 1, 1)));
        assertUnavailable(e, Reason.SCORE_MISMATCH);
    }

    @Test
    void 부르는_쪽_트랜잭션_안에서_불러도_AI_호출은_트랜잭션_밖() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        AtomicBoolean inTx = new AtomicBoolean(true);
        when(aiClient.getDlRankings(d.date)).thenAnswer(inv -> {
            inTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return response(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));
        });
        AtomicBoolean outerTx = new AtomicBoolean(false);

        DlRankingResult result = new TransactionTemplate(txManager).execute(status -> {
            outerTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return getDlRanking.execute(d.date);
        });

        assertThat(outerTx).isTrue();
        assertThat(inTx).isFalse();
        assertThat(result).isInstanceOf(Available.class);
    }

    @Test
    void AI_호출은_트랜잭션_밖에서() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        AtomicBoolean inTx = new AtomicBoolean(true);
        when(aiClient.getDlRankings(d.date)).thenAnswer(inv -> {
            inTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return response(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));
        });

        assertThat(getDlRanking.execute(d.date)).isInstanceOf(Available.class);
        assertThat(inTx).isFalse();
    }

    // ---- 경보 집합 ----

    @Test
    void 경보가_하나_더_있으면_ALERT_SET_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        d.alert(2, r);   // DB 에만
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));

        assertUnavailable(d, Reason.ALERT_SET_MISMATCH);
    }

    @Test
    void 경보가_하나_덜_있으면_ALERT_SET_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1), unscored(d.no(2), r, null)));

        assertUnavailable(d, Reason.ALERT_SET_MISMATCH);
    }

    @Test
    void 처음_스캔이_아니라_같은_건수인데_번호가_밀리면_ALERT_SET_MISMATCH() {
        // 다른 날을 먼저 스캔해 시퀀스가 앞서 감 → 같은 3건이 000004~000006. 스냅샷은 000001~000003
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        for (int n = 4; n <= 6; n++) {
            d.score(d.alert(n, r), "0.5", MODEL);
        }
        respond(d, List.of(
                linked(d.no(1), r, 0.5, 0.5, 1, 1),
                linked(d.no(2), r, 0.5, 0.5, 2, 2),
                linked(d.no(3), r, 0.5, 0.5, 3, 3)));

        assertUnavailable(d, Reason.ALERT_SET_MISMATCH);
    }

    @Test
    void 그날_경보가_0건이면_ALERT_SET_MISMATCH() {
        Day d = newDay();
        respond(d, List.of(unscored(d.no(1), "R02_FAN_IN", null)));

        assertUnavailable(d, Reason.ALERT_SET_MISMATCH);
    }

    // ---- 룰·모델·점수 ----

    @Test
    void 룰이_다르면_RULE_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        respond(d, List.of(linked(d.no(1), "R01_FAN_OUT", 0.5, 0.5, 1, 1)));

        assertUnavailable(d, Reason.RULE_MISMATCH);
    }

    @Test
    void model_version이_다르면_MODEL_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", "mock-0.0.1");
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));

        assertUnavailable(d, Reason.MODEL_MISMATCH);
    }

    @Test
    void 점수_차가_1e_5이면_SCORE_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.92001", MODEL);
        respond(d, List.of(linked(d.no(1), r, 0.92, 0.5, 1, 1)));

        assertUnavailable(d, Reason.SCORE_MISMATCH);
    }

    @Test
    void DB_점수는_있는데_응답_ml_score가_없으면_SCORE_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", MODEL);
        respond(d, List.of(unscored(d.no(1), r, null)));

        assertUnavailable(d, Reason.SCORE_MISMATCH);
    }

    @Test
    void DB_점수는_없는데_응답_ml_score가_있으면_SCORE_MISMATCH() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.alert(1, r);
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1)));

        assertUnavailable(d, Reason.SCORE_MISMATCH);
    }

    @Test
    void 여러_이유가_겹치면_앞_단계_하나_룰이_모델보다_먼저() {
        Day d = newDay();
        String r = d.rule("R02_FAN_IN");
        d.score(d.alert(1, r), "0.5", "old-model");      // 모델도 다름
        d.score(d.alert(2, r), "0.1", MODEL);            // 점수도 다름
        respond(d, List.of(linked(d.no(1), r, 0.5, 0.5, 1, 1), linked(d.no(2), "R01_FAN_OUT", 0.9, 0.5, 2, 2)));

        assertUnavailable(d, Reason.RULE_MISMATCH);
    }

    // ---- AI ----

    @Test
    void AI_503_DL_RANKING_UNAVAILABLE이면_AI_UNAVAILABLE_예외_안_나감() {
        Day d = newDay();
        d.alert(1, d.rule("R02_FAN_IN"));
        when(aiClient.getDlRankings(d.date)).thenThrow(
                new AiClientException("DL_RANKING_UNAVAILABLE", "ML+DL 조사 순위를 지금 쓸 수 없음", Map.of(), 502));

        assertUnavailable(d, Reason.AI_UNAVAILABLE);
    }

    @Test
    void AI_응답_확인_실패_타임아웃_그_밖_예외도_AI_UNAVAILABLE() {
        Day d = newDay();
        when(aiClient.getDlRankings(any())).thenThrow(new AiClientException("AI_BAD_RESPONSE", "x"))
                .thenThrow(new AiClientException("AI_TIMEOUT", "x"))
                .thenThrow(new IllegalStateException("x"));

        for (int i = 0; i < 3; i++) {
            assertUnavailable(d, Reason.AI_UNAVAILABLE);
        }
    }

    // ---- 목업 ----

    @Test
    void 목업_응답은_DB와_안_맞아_ALERT_SET_MISMATCH_2022_09_01은_AI_UNAVAILABLE() {
        GetDlRanking withMock = new GetDlRanking(new MockAiClient(om), repository);
        Day d = newDay();
        d.alert(1, d.rule("R02_FAN_IN"));

        assertThat(withMock.execute(d.date)).isEqualTo(new Unavailable(Reason.ALERT_SET_MISMATCH));
        assertThat(withMock.execute(LocalDate.of(2022, 9, 1))).isEqualTo(new Unavailable(Reason.AI_UNAVAILABLE));
    }

    // ---- 도움 ----

    private void assertUnavailable(Day d, Reason reason) {
        assertThat(getDlRanking.execute(d.date)).isEqualTo(new Unavailable(reason));
    }

    private void respond(Day d, List<DlRankedAlert> alerts) {
        when(aiClient.getDlRankings(d.date)).thenReturn(response(d, alerts));
    }

    /** 건수 칸은 이유별로 센다 — 응답 모양 확인은 shared 몫이라 여기선 맞춰만 둔다. */
    private static DlRankingResponse response(Day d, List<DlRankedAlert> alerts) {
        int linked = 0, mlOnly = 0, unscored = 0;
        for (DlRankedAlert a : alerts) {
            if (a.fallbackReason() == null) linked++;
            else if (a.fallbackReason() == DlFallbackReason.INCOMPLETE_DL_EVIDENCE) mlOnly++;
            else unscored++;
        }
        return new DlRankingResponse(d.date, SHA, MODEL, "dl-test", 10, 8, linked, mlOnly, unscored, alerts);
    }

    private static DlRankedAlert linked(String no, String rule, double ml, double dl, int mlRank, int fusedRank) {
        return new DlRankedAlert(no, rule, ml, null, dl, 1, 1, null, mlRank, fusedRank);
    }

    private static DlRankedAlert incomplete(String no, String rule, double ml, int rank) {
        return new DlRankedAlert(no, rule, ml, null, null, 2, 1, DlFallbackReason.INCOMPLETE_DL_EVIDENCE, rank, rank);
    }

    private static DlRankedAlert unscored(String no, String rule, Double dl) {
        return new DlRankedAlert(no, rule, null, "TRANSACTION_NOT_FOUND", dl, 1, dl == null ? 0 : 1,
                DlFallbackReason.ML_UNSCORED, null, null);
    }

    private Day newDay() {
        return new Day(LocalDate.of(2031, 1, 1).plusDays(DAY.incrementAndGet()));
    }

    /** 하루치 경보. 룰은 테스트마다 새 행(같은 rule_id 라도 버전을 달리) — 경보 번호는 `ALT-{날짜}-{n 6자리}`. */
    private final class Day {
        final LocalDate date;
        final List<Long> batchRuns = new ArrayList<>();
        final Map<String, Long> ruleRefs = new HashMap<>();

        Day(LocalDate date) {
            this.date = date;
        }

        String no(int n) {
            return "ALT-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", n);
        }

        /** 끈 새 버전 행을 만들고 rule_id 를 돌려준다(응답 rule_id 와 같은 문자열). */
        String rule(String ruleId) {
            Long ruleRefId = jdbc.queryForObject("""
                    insert into ops.rules(rule_id, version, name, typology_hint, params, is_active, created_by, updated_by)
                    values (?, ?, '테스트 룰', null, '{}'::jsonb, false, 1, 1) returning id""",
                    Long.class, ruleId, 1000 + (int) SEQUENCE.incrementAndGet());
            ruleRefs.put(ruleId, ruleRefId);
            return ruleId;
        }

        long alert(int n, String ruleId) {
            Long ruleRefId = ruleRefs.get(ruleId);
            if (batchRuns.isEmpty()) {
                batchRuns.add(jdbc.queryForObject("""
                        insert into ops.batch_runs(batch_type, batch_date, status, triggered_by)
                        values ('RULE_SCAN', ?, 'RUNNING', 1) returning id""", Long.class, date));
            }
            long seq = SEQUENCE.incrementAndGet();
            return jdbc.queryForObject("""
                    insert into ops.alerts(alert_no, batch_run_id, rule_ref_id, account_key, entity_id, window_start,
                                           window_end, reference_ts, rule_facts, batch_date)
                    values (?, ?, ?, ?, null, ?, ?, ?, '{}'::jsonb, ?) returning id""", Long.class,
                    no(n), batchRuns.get(0), ruleRefId, "10_DL" + seq, date.atStartOfDay().minusDays(1),
                    date.atStartOfDay(), date.atStartOfDay(), date);
        }

        void failed(long alertId, String code) {
            jdbc.update("update ops.alerts set score_status = 'FAILED', score_failure_code = ? where id = ?", code, alertId);
        }

        void score(long alertId, String score, String model) {
            jdbc.update("update ops.alerts set score_status = 'SCORED' where id = ?", alertId);
            jdbc.update("""
                    insert into ops.alert_scores(alert_id, score, threshold, model_version, feature_version,
                                                 prediction_id, produced_at, payload)
                    values (?, ?::numeric, 0.7, ?, 'f1', ?, now(), '{}'::jsonb)""",
                    alertId, score, model, "p-dl-" + alertId);
        }
    }
}
