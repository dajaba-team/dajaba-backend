package com.team.aml.source.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.source.application.LoadTransactionFile;
import com.team.aml.source.application.StagePatternFile;
import com.team.aml.source.domain.SourceFileAlreadyLoadedException;
import com.team.aml.source.domain.TransactionFileFormatException;
import com.team.aml.source.domain.TransactionLoadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TransactionLoadRepositoryTest {

    private static final Path FIXTURE_DIR = Path.of("src/test/resources/fixtures/ibm_small");
    private static final String HEADER = "Timestamp,From Bank,Account,To Bank,Account,Amount Received,"
            + "Receiving Currency,Amount Paid,Payment Currency,Payment Format,Is Laundering";
    private static final String ROW =
            "2022/09/01 00:20,010,A1,020,B1,100.00,US Dollar,90.00,Euro,ACH,0";

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
    LoadTransactionFile loadTransactionFile;

    @Autowired
    StagePatternFile stagePatternFile;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table ops.transactions_staging, ops.patterns_staging");
        jdbc.update("delete from ops.transactions");
        jdbc.update("delete from ops.source_files");
    }

    @Test
    void fixture가_expected_json과_일치한다() throws Exception {
        JsonNode expected = new ObjectMapper().readTree(FIXTURE_DIR.resolve("expected.json").toFile());
        stagePatternFile.execute(FIXTURE_DIR.resolve("HI-Small_Patterns.txt"));

        TransactionLoadResult result = loadTransactionFile.execute(FIXTURE_DIR.resolve("HI-Small_Trans.csv"));

        assertThat(result.fileRows()).isEqualTo(expected.get("rows").asLong());
        assertThat(result.insertedRows()).isEqualTo(expected.get("rows").asLong());
        assertThat(count("select count(*) from ops.transactions where is_laundering"))
                .isEqualTo(expected.get("labeled").asLong());
        assertThat(count("""
                select count(*) from (
                    select from_account_key from ops.transactions
                    union select to_account_key from ops.transactions) a"""))
                .isEqualTo(expected.get("accounts").asLong());
        expected.get("per_day").fields().forEachRemaining(day -> assertThat(count(
                "select count(*) from ops.transactions where ts::date = '" + day.getKey() + "'"))
                .isEqualTo(day.getValue().asLong()));
        assertThat(jdbc.queryForObject(
                "select sum(amount_paid) from ops.transactions where currency_paid = 'US Dollar'", BigDecimal.class))
                .isEqualByComparingTo(expected.get("amount_paid_sum_by_currency").get("US Dollar").decimalValue());
        assertThat(count("select count(*) from ops.transactions where pattern_type is not null")).isEqualTo(56);
        assertThat(count("select count(distinct pattern_id) from ops.transactions")).isEqualTo(7);
    }

    @Test
    void 행을_변환해_저장한다() throws Exception {
        TransactionLoadResult result = load("a.csv", ROW);

        assertThat(result.amountsRounded()).isZero();
        assertThat(jdbc.queryForObject("select ts from ops.transactions", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2022, 9, 1, 0, 20));
        assertThat(jdbc.queryForMap("""
                select from_bank_id, from_bank_id_raw, from_account_no, from_account_key,
                       to_bank_id, to_bank_id_raw, to_account_no, to_account_key,
                       currency_received, currency_paid, payment_format, source_row_number
                from ops.transactions"""))
                .containsEntry("from_bank_id", "10").containsEntry("from_bank_id_raw", "010")
                .containsEntry("from_account_no", "A1").containsEntry("from_account_key", "10_A1")
                .containsEntry("to_bank_id", "20").containsEntry("to_bank_id_raw", "020")
                .containsEntry("to_account_no", "B1").containsEntry("to_account_key", "20_B1")
                .containsEntry("currency_received", "US Dollar").containsEntry("currency_paid", "Euro")
                .containsEntry("payment_format", "ACH").containsEntry("source_row_number", 1L);
        assertThat(jdbc.queryForObject("select amount_received from ops.transactions", BigDecimal.class))
                .isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select amount_paid from ops.transactions", BigDecimal.class))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void 앞_0_개수만_다른_은행은_같은_키로_저장하고_원본을_보존한다() throws Exception {
        load("a.csv",
                "2022/09/01 00:20,010,A1,0010,A1,1.00,Euro,1.00,Euro,ACH,0",
                "2022/09/01 00:21,010,A1,020,A1,1.00,Euro,1.00,Euro,ACH,1");

        assertThat(jdbc.queryForList(
                "select is_self_transfer from ops.transactions order by source_row_number", Boolean.class))
                .containsExactly(true, false);
        assertThat(jdbc.queryForList(
                "select is_laundering from ops.transactions order by source_row_number", Boolean.class))
                .containsExactly(false, true);
        assertThat(jdbc.queryForObject(
                "select to_bank_id_raw from ops.transactions where source_row_number = 1", String.class))
                .isEqualTo("0010");
    }

    @Test
    void 소수_자릿수를_넘는_금액_필드_수를_돌려준다() throws Exception {
        TransactionLoadResult result = load("a.csv",
                "2022/09/01 00:20,010,A1,020,B1,0.001234,Bitcoin,0.001234,Bitcoin,Bitcoin,0",
                "2022/09/01 00:21,010,A1,020,B1,1.500000,Bitcoin,1.00,Euro,Bitcoin,0");

        assertThat(result.amountsRounded()).isEqualTo(2);
    }

    @Test
    void 패턴을_LEFT_JOIN으로_붙이고_없는_거래는_null로_둔다() throws Exception {
        Path patterns = Files.writeString(dir.resolve("patterns.txt"), String.join("\n",
                "BEGIN LAUNDERING ATTEMPT - CYCLE", ROW, "END LAUNDERING ATTEMPT"));
        stagePatternFile.execute(patterns);

        load("a.csv", ROW, "2022/09/01 00:21,010,A2,020,B1,1.00,Euro,1.00,Euro,ACH,0");

        assertThat(jdbc.queryForObject(
                "select pattern_id from ops.transactions where source_row_number = 1", String.class)).isEqualTo("P000001");
        assertThat(jdbc.queryForObject(
                "select pattern_type from ops.transactions where source_row_number = 1", String.class)).isEqualTo("CYCLE");
        assertThat(jdbc.queryForObject(
                "select pattern_id from ops.transactions where source_row_number = 2", String.class)).isNull();
    }

    @Test
    void 같은_파일은_거부하고_다른_파일은_행_번호를_1부터_매긴다() throws Exception {
        load("a.csv", ROW);
        Path same = Files.writeString(dir.resolve("renamed.csv"), content(ROW));

        assertThatThrownBy(() -> loadTransactionFile.execute(same))
                .isInstanceOf(SourceFileAlreadyLoadedException.class);

        load("b.csv", ROW, "2022/09/01 00:21,010,A2,020,B1,1.00,Euro,1.00,Euro,ACH,0");
        assertThat(count("select count(*) from ops.transactions")).isEqualTo(3);
        assertThat(count("select count(*) from ops.source_files")).isEqualTo(2);
        assertThat(count("select count(*) from ops.transactions where source_row_number = 1")).isEqualTo(2);
    }

    @Test
    void 실패_후_다시_적재하면_행_번호가_1부터_맞는다() throws Exception {
        assertThatThrownBy(() -> load("bad.csv", ROW, "2022/99/99 00:00,010,A1,020,B1,1.00,Euro,1.00,Euro,ACH,0"))
                .isInstanceOf(TransactionFileFormatException.class);

        load("good.csv", ROW);

        assertThat(count("select min(source_row_number) from ops.transactions")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("broken")
    void 잘못된_파일은_실패하고_내용을_노출하지_않으며_기존_데이터를_유지한다(String name, String text) throws Exception {
        load("ok.csv", ROW);
        Path file = Files.writeString(dir.resolve("broken.csv"), text);

        assertThatThrownBy(() -> loadTransactionFile.execute(file))
                .isInstanceOf(TransactionFileFormatException.class)
                .hasMessageNotContaining("SECRET");

        assertThat(count("select count(*) from ops.transactions")).isEqualTo(1);
        assertThat(count("select count(*) from ops.source_files")).isEqualTo(1);
        assertThat(count("select count(*) from ops.transactions_staging")).isEqualTo(1);
    }

    static Stream<Arguments> broken() {
        return Stream.of(
                Arguments.of("헤더 다름", "Wrong,Header\n" + ROW + "\n"),
                Arguments.of("빈 파일", ""),
                Arguments.of("데이터 없음", HEADER + "\n"),
                Arguments.of("필드 수 부족", HEADER + "\n2022/09/01 00:20,010,SECRET1\n"),
                Arguments.of("필드 수 초과", HEADER + "\n" + ROW + ",SECRET1\n"),
                Arguments.of("계좌 빈 문자열", HEADER + "\n2022/09/01 00:20,010,\"\",020,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("은행 공백", HEADER + "\n2022/09/01 00:20,   ,A1,020,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("송신 은행 ID 문자", HEADER + "\n2022/09/01 00:20,ABC,SECRET1,020,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("수신 은행 ID 문자", HEADER + "\n2022/09/01 00:20,010,SECRET1,0A0,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("시각 형식 오류", HEADER + "\n2022-09-01 00:20,010,SECRET1,020,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("없는 날짜", HEADER + "\n2022/13/40 00:20,010,SECRET1,020,B1,1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("금액 형식 오류", HEADER + "\n2022/09/01 00:20,010,SECRET1,020,B1,abc,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("음수 금액", HEADER + "\n2022/09/01 00:20,010,SECRET1,020,B1,-1.00,Euro,1.00,Euro,ACH,0\n"),
                Arguments.of("라벨 오류", HEADER + "\n2022/09/01 00:20,010,SECRET1,020,B1,1.00,Euro,1.00,Euro,ACH,2\n"));
    }

    private TransactionLoadResult load(String fileName, String... rows) throws IOException {
        return loadTransactionFile.execute(Files.writeString(dir.resolve(fileName), content(rows)));
    }

    private static String content(String... rows) {
        return HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n";
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
