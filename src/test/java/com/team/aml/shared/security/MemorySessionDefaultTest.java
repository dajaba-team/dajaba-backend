package com.team.aml.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본값(aml.session.redis.enabled=false, AML_SESSION_REDIS_ENABLED 기본)은 SCRUM-273 전과 같다 — 메모리 세션·SessionRegistryImpl,
 * Redis 연결·Spring Session 저장소·필터가 의존성만으로 생기지 않는다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MemorySessionDefaultTest {

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

    @Autowired ApplicationContext ctx;

    @Test
    void 정상_기본값은_메모리_세션이고_Redis_빈이_없다() {
        assertThat(ctx.getBean(SessionRegistry.class)).isInstanceOf(SessionRegistryImpl.class);
        assertThat(ctx.getBeansOfType(HttpSessionEventPublisher.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(SessionRepository.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(SessionRepositoryFilter.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(RedisSessionLogoutHandler.class)).isEmpty();
    }
}
