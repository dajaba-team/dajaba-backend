package com.team.aml.source.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V004_1의 자동 발급, 중복 방지, 계정 권한을 실제 PostgreSQL에서 확인한다. */
@Testcontainers
class SourceSchemaTest {

    private static final String INSERT_TRANSACTION = """
            insert into ops.transactions(
                ts, from_bank_id, from_bank_id_raw, from_account_no, from_account_key,
                to_bank_id, to_bank_id_raw, to_account_no, to_account_key,
                amount_received, currency_received, amount_paid, currency_paid,
                payment_format, is_laundering, is_self_transfer, source_file_id, source_row_number)
            values (
                '2022-09-01 00:20:00', '10', '010', 'A1', '10_A1',
                '20', '020', 'B1', '20_B1',
                100.00, 'US Dollar', 100.00, 'US Dollar',
                'ACH', false, false, ?, ?)
            returning transaction_id
            """;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), "app_migrate", "migrate")
                .schemas("ops")
                .defaultSchema("ops")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void 거래_ID는_DB가_자동으로_발급한다() throws Exception {
        try (Connection c = backend()) {
            long fileId = newSourceFile(c);

            long first = insertTransaction(c, fileId, SEQUENCE.incrementAndGet());
            long second = insertTransaction(c, fileId, SEQUENCE.incrementAndGet());

            assertThat(second).isGreaterThan(first);
        }
    }

    @Test
    void 거래_ID를_직접_넣으면_거부한다() throws Exception {
        try (Connection c = backend()) {
            long fileId = newSourceFile(c);
            String sql = INSERT_TRANSACTION
                    .replace("ts, from_bank_id,", "transaction_id, ts, from_bank_id,")
                    .replace("'2022-09-01 00:20:00',", "1, '2022-09-01 00:20:00',");

            assertSqlState("428C9", () -> {
                try (PreparedStatement statement = c.prepareStatement(sql)) {
                    statement.setLong(1, fileId);
                    statement.setLong(2, SEQUENCE.incrementAndGet());
                    statement.executeQuery();
                }
            });
        }
    }

    @Test
    void 같은_파일의_같은_행은_중복_적재할_수_없다() throws Exception {
        try (Connection c = backend()) {
            long fileId = newSourceFile(c);
            long row = SEQUENCE.incrementAndGet();
            insertTransaction(c, fileId, row);

            assertSqlState("23505", () -> insertTransaction(c, fileId, row));
        }
    }

    @Test
    void 없는_원본_파일은_참조할_수_없다() throws Exception {
        try (Connection c = backend()) {
            assertSqlState("23503", () -> insertTransaction(c, -1L, SEQUENCE.incrementAndGet()));
        }
    }

    @Test
    void 같은_파일_해시는_중복_등록할_수_없다() throws Exception {
        try (Connection c = backend()) {
            String hash = nextHash();
            insertSourceFile(c, hash);

            assertSqlState("23505", () -> insertSourceFile(c, hash));
        }
    }

    @Test
    void 백엔드_계정은_새_스테이징을_비울_수_있다() throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement()) {
            statement.execute("truncate table ops.accounts_staging");
            statement.execute("truncate table ops.patterns_staging");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "accounts", "entities", "customer_attributes", "source_files"})
    void 백엔드_계정은_원천을_수정하거나_삭제할_수_없다(String table) throws Exception {
        try (Connection c = backend(); Statement statement = c.createStatement()) {
            assertSqlState("42501", () -> statement.executeUpdate("update ops." + table + " set created_at = now()"));
            assertSqlState("42501", () -> statement.executeUpdate("delete from ops." + table));
        }
    }

    @Test
    void AI_계정은_원천만_읽고_source_files는_읽지_못한다() throws Exception {
        try (Connection c = connect("app_ai", "ai"); Statement statement = c.createStatement()) {
            statement.executeQuery("select count(*) from ops.transactions");

            assertSqlState("42501", () -> statement.executeQuery("select count(*) from ops.source_files"));
        }
    }

    private static Connection backend() throws SQLException {
        return connect("app_backend", "backend");
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), user, password);
    }

    private static long newSourceFile(Connection c) throws SQLException {
        return insertSourceFile(c, nextHash());
    }

    private static String nextHash() {
        return String.format("%064d", SEQUENCE.incrementAndGet());
    }

    private static long insertSourceFile(Connection c, String hash) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "insert into ops.source_files(file_name, file_hash) values ('test.csv', ?) returning id")) {
            statement.setString(1, hash);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long insertTransaction(Connection c, long fileId, long row) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(INSERT_TRANSACTION)) {
            statement.setLong(1, fileId);
            statement.setLong(2, row);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void assertSqlState(String expected, SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
