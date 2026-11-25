package com.team.aml.source.infrastructure;

import com.team.aml.source.application.LoadAccountFile;
import com.team.aml.source.application.LoadCustomerAttributesFile;
import com.team.aml.source.domain.CustomerAttributesFileFormatException;
import com.team.aml.source.domain.CustomerAttributesLoadResult;
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

/** 가상 KYC 파일 적재(SCRUM-430) — 법인 뒤에 넣고, 계좌 개설 목적 빈칸은 NULL, 파일이 없으면 건너뛴다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CustomerAttributesLoadTest {

    private static final String ACCOUNT_HEADER = "Bank Name,Bank ID,Account Number,Entity ID,Entity Name";
    private static final String HEADER = "entity_id,industry,risk_level,account_purpose,onboarded_at,country_code";

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
    LoadCustomerAttributesFile loadCustomerAttributesFile;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @BeforeEach
    void clean() throws IOException {
        jdbc.update("delete from ops.customer_attributes");
        jdbc.execute("truncate table ops.accounts_staging");
        jdbc.update("delete from ops.accounts");
        jdbc.update("delete from ops.entities");
        Path accounts = Files.writeString(dir.resolve("accounts.csv"), ACCOUNT_HEADER + "\r\n"
                + "Canada Bank #1,1,A1,E1,Corporation #1\r\nCanada Bank #1,1,A2,E2,Individual #2\r\n");
        loadAccountFile.execute(accounts);
    }

    @Test
    void 법인_뒤에_KYC를_넣고_계좌_개설_목적_빈칸은_NULL이다() throws Exception {
        CustomerAttributesLoadResult result = load(
                "E1,RETAIL,MEDIUM,SALES_DEPOSIT,2022-01-01,US",
                "E2,RETAIL,LOW,,2017-09-20,US");

        assertThat(result).isEqualTo(new CustomerAttributesLoadResult(true, 2, 2));
        assertThat(text("select account_purpose from ops.customer_attributes where entity_id = 'E1'"))
                .isEqualTo("SALES_DEPOSIT");
        assertThat(text("select account_purpose from ops.customer_attributes where entity_id = 'E2'")).isNull();
        assertThat(text("select risk_level from ops.customer_attributes where entity_id = 'E2'")).isEqualTo("LOW");
        assertThat(text("select onboarded_at::text from ops.customer_attributes where entity_id = 'E2'"))
                .isEqualTo("2017-09-20");
    }

    @Test
    void 파일이_없으면_건너뛰고_아무것도_넣지_않는다() {
        CustomerAttributesLoadResult result = loadCustomerAttributesFile.execute(dir.resolve("customer_attributes.csv"));

        assertThat(result).isEqualTo(CustomerAttributesLoadResult.SKIPPED);
        assertThat(count("select count(*) from ops.customer_attributes")).isZero();
    }

    @Test
    void 법인이_없거나_이미_들어_있는_행은_건너뛰고_BOM_헤더도_읽는다() throws Exception {
        load("E1,RETAIL,MEDIUM,SALES_DEPOSIT,2022-01-01,US");

        Path file = Files.writeString(dir.resolve("again.csv"), (char) 0xFEFF + HEADER + "\r\n"
                + "E1,OTHER,HIGH,PERSONAL,2020-01-01,US\r\nE9,RETAIL,MEDIUM,,2020-01-01,US\r\n");
        CustomerAttributesLoadResult result = loadCustomerAttributesFile.execute(file);

        assertThat(result).isEqualTo(new CustomerAttributesLoadResult(true, 2, 0));
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(text("select industry from ops.customer_attributes where entity_id = 'E1'")).isEqualTo("RETAIL");
        assertThat(count("select count(*) from ops.customer_attributes where entity_id = 'E9'")).isZero();
    }

    @Test
    void 법인이_없는_행은_값이_틀려도_건너뛰고_따옴표_빈_목적은_NULL이다() throws Exception {   // #190 리뷰 — 건너뛰기가 형식 검사보다 먼저
        CustomerAttributesLoadResult result = load(
                "E9,RETAIL,MEDIUM,,NOT-A-DATE,USA",
                "E1,RETAIL,MEDIUM,\"\",2022-01-01,US");

        assertThat(result).isEqualTo(new CustomerAttributesLoadResult(true, 2, 1));
        assertThat(text("select account_purpose from ops.customer_attributes where entity_id = 'E1'")).isNull();
        assertThat(count("select count(*) from ops.customer_attributes where entity_id = 'E9'")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("broken")
    void 형식이_틀리면_실패하고_내용을_노출하지_않으며_넣지_않는다(String name, String text) throws Exception {
        Path file = Files.writeString(dir.resolve("broken.csv"), text);

        assertThatThrownBy(() -> loadCustomerAttributesFile.execute(file))
                .isInstanceOf(CustomerAttributesFileFormatException.class)
                .hasMessageStartingWith("KYC 파일: ")
                .hasMessageNotContaining("SECRET");

        assertThat(count("select count(*) from ops.customer_attributes")).isZero();
    }

    static Stream<Arguments> broken() {
        return Stream.of(
                Arguments.of("헤더 다름", "id,industry\nE1,SECRET\n"),
                Arguments.of("빈 파일", ""),
                Arguments.of("날짜 형식", HEADER + "\nE1,RETAIL,MEDIUM,,SECRET-DATE,US\n"),
                Arguments.of("위험등급 값", HEADER + "\nE1,RETAIL,SECRET,,2022-01-01,US\n"),
                Arguments.of("국가코드 길이", HEADER + "\nE1,RETAIL,MEDIUM,,2022-01-01,SECRET\n"),
                Arguments.of("업종 누락", HEADER + "\nE1,,MEDIUM,,2022-01-01,US\n"),
                Arguments.of("필드 수 부족", HEADER + "\nE1,RETAIL,SECRET\n"));
    }

    private CustomerAttributesLoadResult load(String... rows) throws IOException {
        Path file = Files.writeString(dir.resolve("customer_attributes.csv"),
                HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n");
        return loadCustomerAttributesFile.execute(file);
    }

    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
