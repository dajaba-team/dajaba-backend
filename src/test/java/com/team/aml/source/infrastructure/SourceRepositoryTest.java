package com.team.aml.source.infrastructure;

import com.team.aml.source.domain.Transaction;
import com.team.aml.source.domain.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 적재된 거래를 도메인 리포지토리로 읽었을 때 값이 그대로인지 실제 PostgreSQL에서 확인한다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SourceRepositoryTest {

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
    TransactionRepository transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 적재_여부와_거래_필드를_그대로_읽는다() {
        assertThat(transactions.existsBy()).isFalse();

        Long fileId = jdbc.queryForObject(
                "insert into ops.source_files(file_name, file_hash) values ('t.csv', ?) returning id",
                Long.class, "a".repeat(64));
        Long id = jdbc.queryForObject("""
                insert into ops.transactions(
                    ts, from_bank_id, from_bank_id_raw, from_account_no, from_account_key,
                    to_bank_id, to_bank_id_raw, to_account_no, to_account_key,
                    amount_received, currency_received, amount_paid, currency_paid,
                    payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number)
                values ('2022-09-01 00:20:00', '10', '010', 'A1', '10_A1',
                        '20', '020', 'B1', '20_B1',
                        3697.34, 'US Dollar', 3697.34, 'US Dollar',
                        'ACH', false, false, ?, 7)
                returning transaction_id
                """, Long.class, fileId);

        assertThat(transactions.existsBy()).isTrue();
        Transaction t = transactions.findById(id).orElseThrow();
        assertThat(t.getTs()).isEqualTo(LocalDateTime.of(2022, 9, 1, 0, 20));
        assertThat(t.getAmountPaid()).isEqualByComparingTo(new BigDecimal("3697.34"));
        assertThat(t.getFromBankId()).isEqualTo("10");
        assertThat(t.getFromBankIdRaw()).isEqualTo("010");
        assertThat(t.getSourceFileId()).isEqualTo(fileId);
        assertThat(t.getSourceRowNumber()).isEqualTo(7L);
    }
}
