package com.team.aml.source.infrastructure;

import com.team.aml.source.application.LoadAccountFile;
import com.team.aml.source.domain.AccountFileFormatException;
import com.team.aml.source.domain.AccountLoadResult;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountLoadRepositoryTest {

    private static final String HEADER = "Bank Name,Bank ID,Account Number,Entity ID,Entity Name";

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
    LoadAccountFile loadAccountFile;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table ops.accounts_staging");
        jdbc.update("delete from ops.accounts");
        jdbc.update("delete from ops.entities");
    }

    @Test
    void 법인을_먼저_넣고_계좌를_정규화해_적재한다() throws Exception {
        AccountLoadResult result = load("a.csv",
                "Canada Bank #26,023842,A1,E1,Corporation #1",
                "Canada Bank #26,023842,A2,E1,Corporation #1");

        assertThat(result).isEqualTo(new AccountLoadResult(2, 1, 2));
        assertThat(text("select bank_id from ops.accounts where account_key = '23842_A1'")).isEqualTo("23842");
        assertThat(text("select account_no from ops.accounts where account_key = '23842_A1'")).isEqualTo("A1");
        assertThat(text("select bank_name from ops.accounts where account_key = '23842_A1'")).isEqualTo("Canada Bank #26");
        assertThat(text("select entity_id from ops.accounts where account_key = '23842_A1'")).isEqualTo("E1");
    }

    @Test
    void 거래_키와_같은_규칙으로_연결된다() throws Exception {
        load("a.csv", "Canada Bank #26,23842,A1,E1,Corporation #1", "Canada Bank #1,000,A2,E1,Corporation #1");
        String transactionKey = AccountLoadJdbcRepository.normalizedBankId("'023842'") + " || '_A1'";

        assertThat(count("select count(*) from ops.accounts where account_key = " + transactionKey)).isEqualTo(1);
        assertThat(count("select count(*) from ops.accounts where account_key = '0_A2'")).isEqualTo(1);
    }

    @Test
    void 법인_유형을_이름에서_파생한다() throws Exception {
        load("a.csv",
                "Bank,1,A1,E1,Corporation #1",
                "Bank,1,A2,E2,Partnership #2",
                "Bank,1,A3,E3,Sole Proprietorship #3",
                "Bank,1,A4,E4,Individual #4",
                "Bank,1,A5,E5,Country #5",
                "Bank,1,A6,E6,Direct #6");

        assertThat(jdbc.queryForList("select entity_type from ops.entities order by entity_id", String.class))
                .containsExactly("CORPORATION", "PARTNERSHIP", "SOLE_PROPRIETORSHIP", "INDIVIDUAL", "COUNTRY", "DIRECT");
    }

    @Test
    void 국가명_은행만_bank_country를_채운다() throws Exception {
        load("a.csv",
                "Saudi Arabia Bank #1,1,A1,E1,Corporation #1",
                "Crytpo Bank #2,2,A2,E1,Corporation #1",
                "Oasis Bank #3,3,A3,E1,Corporation #1",
                "First Bank of Dallas,4,A4,E1,Corporation #1",
                ",5,A5,E1,Corporation #1");

        assertThat(text("select bank_country from ops.accounts where account_key = '1_A1'")).isEqualTo("Saudi Arabia");
        assertThat(count("select count(*) from ops.accounts where bank_country is null")).isEqualTo(4);
    }

    @Test
    void 다음_파일은_새_계좌만_넣고_처음_값을_유지한다() throws Exception {
        load("a.csv", "Canada Bank #1,1,A1,E1,Corporation #1");

        AccountLoadResult second = load("b.csv",
                "Japan Bank #9,1,A1,E1,Corporation #1",
                "Japan Bank #9,1,A2,E2,Partnership #2");

        assertThat(second).isEqualTo(new AccountLoadResult(2, 1, 1));
        assertThat(text("select bank_country from ops.accounts where account_key = '1_A1'")).isEqualTo("Canada");
        assertThat(count("select count(*) from ops.accounts")).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("broken")
    void 잘못된_파일은_실패하고_내용을_노출하지_않으며_기존_데이터를_유지한다(String name, String text) throws Exception {
        load("ok.csv", "Canada Bank #1,1,A1,E1,Corporation #1");
        Path file = Files.writeString(dir.resolve("broken.csv"), text);

        assertThatThrownBy(() -> loadAccountFile.execute(file))
                .isInstanceOf(AccountFileFormatException.class)
                .hasMessageNotContaining("SECRET");

        assertThat(count("select count(*) from ops.accounts")).isEqualTo(1);
        assertThat(count("select count(*) from ops.entities")).isEqualTo(1);
        assertThat(count("select count(*) from ops.accounts_staging")).isEqualTo(1);
    }

    static Stream<Arguments> broken() {
        return Stream.of(
                Arguments.of("헤더 다름", "Wrong,Header\nBank,1,A9,E9,Corporation #9\n"),
                Arguments.of("빈 파일", ""),
                Arguments.of("데이터 없음", HEADER + "\n"),
                Arguments.of("필드 수 부족", HEADER + "\nBank,1,SECRET1\n"),
                Arguments.of("필드 수 초과", HEADER + "\nBank,1,SECRET1,E9,Corporation #9,x\n"),
                Arguments.of("계좌번호 누락", HEADER + "\nBank,1,,E9,Corporation #9\n"),
                Arguments.of("모르는 법인 유형", HEADER + "\nBank,1,A9,E9,SECRET Trust #9\n"),
                Arguments.of("법인 이름 충돌", HEADER + "\nBank,1,A8,E9,Corporation #8\nBank,1,A9,E9,Corporation #9\n"));
    }

    private AccountLoadResult load(String fileName, String... rows) throws IOException {
        Path file = Files.writeString(dir.resolve(fileName), HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n");
        return loadAccountFile.execute(file);
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
