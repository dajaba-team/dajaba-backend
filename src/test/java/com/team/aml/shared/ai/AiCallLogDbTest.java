package com.team.aml.shared.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 PostgreSQL 에서 독립 커밋·메타데이터 스키마·추가 전용 권한을 검증한다. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AiCallLogDbTest {

    @Container
    @ServiceConnection(type = JdbcConnectionDetails.class)
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("aml")
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("APP_MIGRATE_PASSWORD", "migrate")
            .withEnv("APP_BACKEND_PASSWORD", "backend")
            .withEnv("APP_AI_PASSWORD", "ai")
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01_roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01_roles.sh");

    // Flyway 는 실제 DDL 소유자로 실행해야 기본 권한·revoke 를 검증할 수 있다.
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired AiCallLogWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Flyway flyway;

    @Test
    void 정상_외부_트랜잭션이_롤백되어도_기록은_남는다() {
        String requestId = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.write(AiCallLog.create("/v1/health", requestId, 1, 200, null, 3,
                    null, null, null, null));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from ops.ai_call_logs where request_id = ?",
                Long.class, requestId)).isEqualTo(1L);
    }

    @Test
    void 경계_본문없이_정확히_열두_컬럼이다() {
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'ops' and table_name = 'ai_call_logs'
                """, String.class)).containsExactlyInAnyOrder("id", "endpoint", "request_id", "attempt",
                "status_code", "error_code", "duration_ms", "ai_version", "generation_id",
                "target_type", "target_id", "created_at");
    }

    @Test
    void 경계_V007은_외래키없이_독립_적용된다() {
        assertThat(Arrays.stream(flyway.info().applied())
                .map(m -> Integer.parseInt(m.getVersion().getVersion())).toList())
                .containsSubsequence(1, 2, 3, 4, 7);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where table_schema = 'ops' and table_name = 'ai_call_logs' and constraint_type = 'FOREIGN KEY'
                """, Long.class)).isZero();
    }

    @Test
    void 실패_backend는_추가만_가능하고_수정과_삭제는_거부된다() throws SQLException {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
             var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("""
                    insert into ops.ai_call_logs (endpoint, request_id, attempt, duration_ms)
                    values ('/v1/health', 'permission-test', 1, 1)
                    """)).isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate("update ops.ai_call_logs set duration_ms = 0"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeUpdate("delete from ops.ai_call_logs"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void 실패_정의하지_않은_target_type은_거부된다() throws SQLException {
        try (var connection = DriverManager.getConnection(db.getJdbcUrl(), "app_backend", "backend");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    insert into ops.ai_call_logs (endpoint, request_id, attempt, duration_ms, target_type)
                    values ('/v1/health', 'invalid-target', 1, 1, 'FOO')
                    """))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
    }
}
