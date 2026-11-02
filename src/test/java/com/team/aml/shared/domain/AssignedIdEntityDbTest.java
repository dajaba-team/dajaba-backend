package com.team.aml.shared.domain;

import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssignedIdEntity (SCRUM-68) — 미리 받은 id 로 save() 하면 merge 가 아니라 INSERT 한 번, 감사 4칸은 그대로 자동.
 * 테이블은 테스트 전용 V9000(ops.test_assigned_items). 테스트마다 다른 id 를 써서 같은 컨테이너 안에서 섞이지 않게 한다.
 * 세션이 없으므로 감사 값은 SYSTEM(1L).
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class AssignedIdEntityDbTest {

    private static final long SYSTEM = 1L;

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

    // Flyway 는 실제 DDL 소유자(app_migrate)로 — AiCallLogDbTest·AccessLogDbTest 와 같음
    @DynamicPropertySource
    static void migrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", db::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "app_migrate");
        registry.add("spring.flyway.password", () -> "migrate");
    }

    @Autowired TestAssignedItemRepository items;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    private Statistics stats;

    @BeforeEach
    void resetStats() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_미리_받은_id_42로_save하면_INSERT_한_번이고_다시_읽으면_감사_4칸이_채워져_있다() {
        TestAssignedItem item = new TestAssignedItem(42L, "audited");
        assertThat(item.isNew()).isTrue();

        items.save(item);

        assertThat(stats.getEntityInsertCount()).isEqualTo(1);
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).as("merge 면 SELECT 가 하나 더 나간다").isEqualTo(1);
        assertThat(item.isNew()).as("@PostPersist 뒤에는 새 행 아님").isFalse();

        TestAssignedItem loaded = items.findById(42L).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(42L);
        assertThat(loaded.isNew()).as("@PostLoad 뒤에는 새 행 아님").isFalse();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getCreatedBy()).isEqualTo(SYSTEM);
        assertThat(loaded.getUpdatedBy()).isEqualTo(SYSTEM);

        Map<String, Object> row = jdbc.queryForMap("select * from ops.test_assigned_items where item_id = ?", 42L);
        assertThat(row.get("name")).isEqualTo("audited");
        assertThat(row.get("created_by")).isEqualTo(SYSTEM);
        assertThat(row.get("updated_by")).isEqualTo(SYSTEM);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_저장한_엔티티를_고쳐_save하면_UPDATE이고_created는_그대로_updated_at만_바뀐다() throws InterruptedException {
        TestAssignedItem item = items.save(new TestAssignedItem(43L, "before"));
        Map<String, Object> first = jdbc.queryForMap("select * from ops.test_assigned_items where item_id = 43");

        Thread.sleep(20);   // timestamptz 는 마이크로초 — 같은 시각이 찍히지 않게
        stats.clear();
        item.rename("after");
        items.save(item);

        assertThat(stats.getEntityInsertCount()).isZero();
        assertThat(stats.getEntityUpdateCount()).isEqualTo(1);
        Map<String, Object> second = jdbc.queryForMap("select * from ops.test_assigned_items where item_id = 43");
        assertThat(second.get("name")).isEqualTo("after");
        assertThat(second.get("created_at")).isEqualTo(first.get("created_at"));
        assertThat(second.get("created_by")).isEqualTo(first.get("created_by"));
        assertThat(((Timestamp) second.get("updated_at")).toInstant())
                .isAfter(((Timestamp) first.get("updated_at")).toInstant());
        assertThat(jdbc.queryForObject("select count(*) from ops.test_assigned_items where item_id = 43", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void 경계_id가_null이면_만들_수_없다() {
        assertThatThrownBy(() -> new TestAssignedItem(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_같은_id로_두_번째_새_엔티티를_save하면_PK_위반이고_첫_행은_그대로() {
        items.save(new TestAssignedItem(44L, "original"));

        assertThatThrownBy(() -> items.save(new TestAssignedItem(44L, "overwrite")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select name from ops.test_assigned_items where item_id = 44", String.class))
                .as("merge 로 조용히 덮어쓰지 않는다").isEqualTo("original");
    }

    // --- 회귀: BaseEntity 를 AuditedEntity 밑으로 옮긴 뒤에도 그대로 ---------------

    @Test
    void 회귀_BaseEntity_하위_User는_id_자동_발급과_감사_4칸이_그대로() {
        User saved = users.save(User.create("scrum68_reg", "회귀 확인", Role.INVESTIGATOR, null));

        assertThat(saved.getId()).isNotNull().isGreaterThan(SYSTEM);
        Map<String, Object> row = jdbc.queryForMap("select * from ops.users where id = ?", saved.getId());
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("created_by")).isEqualTo(SYSTEM);
        assertThat(row.get("updated_by")).isEqualTo(SYSTEM);
    }
}
