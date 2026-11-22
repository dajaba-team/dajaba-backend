package com.team.aml.shared.batch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 공통 지표 (SCRUM-349) — 실제 PostgreSQL·JPA. 끝난 실행은 커밋 뒤 한 번만, 롤백되면 안 센다.
 * 배치 잇기의 "그 뒤 시작된 실행" 조회도 같이 본다. 지표는 테스트끼리 누적되므로 앞뒤 차이로 비교한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class BatchRunMetricsTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long SYSTEM = 1L;

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

    @Autowired BatchRunRepository batchRuns;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 정상_성공으로_커밋되면_유형_결과별_시간과_in_out_건수를_한_번_센다() {
        long timerBefore = timerCount("SCORE_REQUEST", "SUCCEEDED");
        double inBefore = rows("SCORE_REQUEST", "in");
        double outBefore = rows("SCORE_REQUEST", "out");
        long id = started(BatchType.SCORE_REQUEST);

        inTx(() -> batchRuns.findById(id).orElseThrow().succeed(30, 28));

        assertThat(timerCount("SCORE_REQUEST", "SUCCEEDED")).isEqualTo(timerBefore + 1);
        assertThat(rows("SCORE_REQUEST", "in")).isEqualTo(inBefore + 30);
        assertThat(rows("SCORE_REQUEST", "out")).isEqualTo(outBefore + 28);
    }

    @Test
    void 정상_실패로_끝나면_FAILED로_세고_건수는_세지_않는다() {
        long failedBefore = timerCount("OPEN_CASES", "FAILED");
        double inBefore = rows("OPEN_CASES", "in");
        long id = started(BatchType.OPEN_CASES);

        inTx(() -> batchRuns.findById(id).orElseThrow().fail("대상 4 · 실패 1"));

        assertThat(timerCount("OPEN_CASES", "FAILED")).isEqualTo(failedBefore + 1);
        assertThat(rows("OPEN_CASES", "in")).isEqualTo(inBefore);
    }

    @Test
    void 경계_롤백되면_세지_않는다() {
        long before = timerCount("LOAD_DATASET", "SUCCEEDED");
        long id = started(BatchType.LOAD_DATASET);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            batchRuns.findById(id).orElseThrow().succeed(1, 1);
            batchRuns.flush();   // 콜백은 flush 때 불림 — 그래도 커밋 전이라 세지 않아야 함
            status.setRollbackOnly();
        });

        assertThat(timerCount("LOAD_DATASET", "SUCCEEDED")).isEqualTo(before);
        assertThat(batchRuns.findById(id).orElseThrow().getStatus()).isEqualTo(BatchStatus.RUNNING);
    }

    @Test
    void 경계_실행_전에도_유형_결과_조합이_0으로_보인다() {   // 첫 실행이 Prometheus increase() 에서 빠지지 않게(한 번만 도는 적재)
        for (BatchType type : BatchType.values()) {
            for (String result : List.of("SUCCEEDED", "FAILED")) {
                assertThat(meters.find("aml.batch.duration").tag("type", type.name()).tag("result", result).timer())
                        .as(type + " " + result).isNotNull();
            }
            for (String kind : List.of("in", "out")) {
                assertThat(meters.find("aml.batch.rows").tag("type", type.name()).tag("kind", kind).counter())
                        .as(type + " " + kind).isNotNull();
            }
        }
        assertThat(meters.find("aml.rule.scan.duration").tag("rule", "R05_SCATTER_GATHER").timer()).isNotNull();
    }

    @Test
    void 경계_시작만_한_실행은_세지_않는다() {
        long running = meters.find("aml.batch.duration").tag("type", "RULE_SCAN").timers().stream()
                .mapToLong(Timer::count).sum();

        started(BatchType.RULE_SCAN);

        assertThat(meters.find("aml.batch.duration").tag("type", "RULE_SCAN").timers().stream()
                .mapToLong(Timer::count).sum()).isEqualTo(running);
    }

    @Test
    void 배치_잇기_조회는_그_시각_뒤에_시작된_같은_유형_날짜만_찾는다() {
        LocalDate d = newDate();
        Instant before = Instant.now();
        started(BatchType.SCORE_REQUEST, d);

        assertThat(batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(BatchType.SCORE_REQUEST, d, before))
                .isTrue();
        assertThat(batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(
                BatchType.SCORE_REQUEST, d, Instant.now().plusSeconds(60))).isFalse();
        assertThat(batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(BatchType.OPEN_CASES, d, before))
                .isFalse();
        assertThat(batchRuns.existsByBatchTypeAndBatchDateAndStartedAtGreaterThanEqual(
                BatchType.SCORE_REQUEST, d.plusDays(1), before)).isFalse();
    }

    private long started(BatchType type) {
        return started(type, newDate());
    }

    private long started(BatchType type, LocalDate date) {
        return batchRuns.save(BatchRun.start(type, date, SYSTEM)).getId();
    }

    private void inTx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private long timerCount(String type, String result) {
        Timer timer = meters.find("aml.batch.duration").tag("type", type).tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    private double rows(String type, String kind) {
        var counter = meters.find("aml.batch.rows").tag("type", type).tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    private static LocalDate newDate() {
        return LocalDate.of(2031, 1, 1).plusDays(SEQUENCE.incrementAndGet());
    }
}
