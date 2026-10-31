package com.team.aml.source.infrastructure;

import com.team.aml.source.application.StagePatternFile;
import com.team.aml.source.domain.PatternFileFormatException;
import com.team.aml.source.domain.PatternMatchStats;
import com.team.aml.source.domain.PatternRow;
import com.team.aml.source.domain.PatternStagingRepository;
import com.team.aml.source.domain.PatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PatternStagingRepositoryTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/ibm_small/HI-Small_Patterns.txt");

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

    @Autowired
    StagePatternFile stagePatternFile;

    @Autowired
    PatternStagingRepository patterns;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanStaging() {
        jdbc.execute("truncate table ops.patterns_staging, ops.transactions_staging");
        jdbc.update("delete from ops.transactions");
        jdbc.update("delete from ops.source_files");
    }

    @Test
    void 적재된_패턴이_없으면_1번부터_매긴다() {
        stagePatternFile.execute(FIXTURE);

        assertThat(jdbc.queryForObject("select min(pattern_id) from ops.patterns_staging", String.class))
                .isEqualTo("P000001");
    }

    @Test
    void 이미_적재된_마지막_번호_다음부터_매긴다() {
        insertLoadedTransaction("P000370");

        stagePatternFile.execute(FIXTURE);

        assertThat(jdbc.queryForObject("select min(pattern_id) from ops.patterns_staging", String.class))
                .isEqualTo("P000371");
        assertThat(jdbc.queryForObject("select max(pattern_id) from ops.patterns_staging", String.class))
                .isEqualTo("P000377");
    }

    @Test
    void 파일을_파싱해_스테이징에_저장한다() {
        int events = stagePatternFile.execute(FIXTURE);

        assertThat(events).isEqualTo(7);
        assertThat(count("select count(*) from ops.patterns_staging")).isEqualTo(56);
        assertThat(count("select count(distinct pattern_id) from ops.patterns_staging")).isEqualTo(7);
        assertThat(eventsOf("FAN_OUT")).isEqualTo(2);
        assertThat(eventsOf("FAN_IN")).isEqualTo(2);
        assertThat(eventsOf("CYCLE")).isEqualTo(2);
        assertThat(eventsOf("STACK")).isEqualTo(1);
    }

    @Test
    void 다시_실행해도_행이_쌓이지_않는다() {
        stagePatternFile.execute(FIXTURE);
        stagePatternFile.execute(FIXTURE);

        assertThat(count("select count(*) from ops.patterns_staging")).isEqualTo(56);
    }

    @Test
    void 잘못된_파일이면_실패하고_기존_행을_유지한다(@TempDir Path dir) throws Exception {
        stagePatternFile.execute(FIXTURE);
        Path broken = Files.writeString(dir.resolve("broken.txt"), "BEGIN LAUNDERING ATTEMPT - UNKNOWN\n");

        assertThatThrownBy(() -> stagePatternFile.execute(broken))
                .isInstanceOf(PatternFileFormatException.class);

        assertThat(count("select count(*) from ops.patterns_staging")).isEqualTo(56);
    }

    @Test
    void 저장_중_DB_오류가_나면_TRUNCATE까지_롤백한다(@TempDir Path dir) throws Exception {
        stagePatternFile.execute(FIXTURE);
        Path badValue = Files.writeString(dir.resolve("bad_value.txt"), String.join("\n",
                "BEGIN LAUNDERING ATTEMPT - CYCLE",
                "2022/09/07 16:27,034,A\0,028,B1,1.00,US Dollar,1.00,US Dollar,Wire,1",
                "END LAUNDERING ATTEMPT"));

        assertThatThrownBy(() -> stagePatternFile.execute(badValue))
                .isNotInstanceOf(PatternFileFormatException.class);

        assertThat(count("select count(*) from ops.patterns_staging")).isEqualTo(56);
    }

    @Test
    void 동시_실행은_앞선_트랜잭션이_끝난_뒤_이어진_번호를_매긴다() throws Exception {
        AtomicReference<CompletableFuture<Integer>> second = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            stagePatternFile.execute(FIXTURE);
            insertLoadedTransaction("P000007");
            second.set(CompletableFuture.supplyAsync(() -> stagePatternFile.execute(FIXTURE)));
            awaitBlockedByLock();
        });
        second.get().get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select min(pattern_id) from ops.patterns_staging", String.class))
                .isEqualTo("P000008");
    }

    @Test
    void 매칭_단일_미매칭_다중_건수를_센다() {
        patterns.saveAll(List.of(
                row("P000001", "A1"),
                row("P000002", "A2"),
                row("P000003", "A3"),
                row("P000004", "A4"),
                row("P000005", "A4")));
        insertTransaction("A1");
        insertTransaction("A3");
        insertTransaction("A3");
        insertTransaction("A4");

        assertThat(patterns.matchStats()).isEqualTo(new PatternMatchStats(1, 1, 3));
    }

    @Test
    void 비어_있으면_모두_0이다() {
        assertThat(patterns.matchStats()).isEqualTo(new PatternMatchStats(0, 0, 0));
    }

    @Test
    void 같은_키의_패턴이_있어도_거래_JOIN_행_수가_늘지_않는다() {
        patterns.saveAll(List.of(row("P000001", "A1"), row("P000002", "A1")));
        insertTransaction("A1");
        insertTransaction("B1");

        String join = "select count(*) from ops.transactions_staging t left join ("
                + PatternStagingJdbcRepository.DISTINCT_PATTERNS + ") p on "
                + PatternStagingJdbcRepository.MATCH_CONDITION;
        assertThat(count(join)).isEqualTo(2);

        String joinedId = "select p.pattern_id from ops.transactions_staging t join ("
                + PatternStagingJdbcRepository.DISTINCT_PATTERNS + ") p on "
                + PatternStagingJdbcRepository.MATCH_CONDITION;
        assertThat(jdbc.queryForObject(joinedId, String.class)).isEqualTo("P000001");
    }

    private long eventsOf(String type) {
        return jdbc.queryForObject(
                "select count(distinct pattern_id) from ops.patterns_staging where pattern_type = ?",
                Long.class, type);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static PatternRow row(String patternId, String fromAccount) {
        return new PatternRow(patternId, PatternType.CYCLE, "2022/09/07 16:27", "034", fromAccount, "028", "B1",
                "100.00", "US Dollar", "100.00", "US Dollar", "Wire", "1");
    }

    private void awaitBlockedByLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'") == 0) {
            assertThat(System.nanoTime()).as("두 번째 실행이 잠금을 기다려야 한다").isLessThan(deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }

    private void insertLoadedTransaction(String patternId) {
        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('t.csv', ?) returning id",
                Long.class, "a".repeat(64));
        jdbc.update("""
                insert into ops.transactions(
                    ts, from_bank_id, from_bank_id_raw, from_account_no, from_account_key,
                    to_bank_id, to_bank_id_raw, to_account_no, to_account_key,
                    amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, pattern_type, pattern_id,
                    source_file_id, source_row_number)
                values ('2022-09-01 00:20:00', '10', '010', 'A1', '10_A1',
                        '20', '020', 'B1', '20_B1',
                        100.00, 'US Dollar', 100.00, 'US Dollar',
                        'ACH', true, false, 'CYCLE', ?, ?, 1)
                """, patternId, fileId);
    }

    private void insertTransaction(String fromAccount) {
        jdbc.update("""
                insert into ops.transactions_staging(
                    timestamp_text, from_bank, from_account, to_bank, to_account, amount_received,
                    receiving_currency, amount_paid, payment_currency, payment_format, is_laundering)
                values ('2022/09/07 16:27', '034', ?, '028', 'B1', '100.00',
                        'US Dollar', '100.00', 'US Dollar', 'Wire', '1')
                """, fromAccount);
    }
}
