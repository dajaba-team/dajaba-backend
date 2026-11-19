package com.team.aml.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * 로그인 세션을 Redis(db 0, 키 {@code aml:session:*})에 둔다 — {@code aml.session.redis.enabled=true}
 * ({@code AML_SESSION_REDIS_ENABLED}) 일 때만 (SCRUM-273). 끄면 {@link MemorySessionConfig}.
 *
 * <ul>
 *   <li>Redis 자동 설정은 application.yml 에서 막아 두고 여기서만 가져온다 — 의존성만으로는 연결하지 않는다.
 *       세션 저장소는 Spring Boot 세션 자동 설정(repository-type indexed)이 이 연결을 보고 만든다.
 *       쿠키(SESSION·HttpOnly·SameSite=Lax·prod 만 Secure)와 만료(30m)는 {@code server.servlet.session.*} 그대로 따라간다.</li>
 *   <li>SessionRegistry 는 Spring Session 의 principal name(= login_id) 색인으로 찾는다.
 *       maximumSessions(1) 와 UserSessions 가 이 빈 하나를 같이 쓴다.</li>
 *   <li>키 공간 알림 구독은 띄우지 않는다 — 세션 이벤트를 쓰는 곳이 없고(HttpSessionEventPublisher 도 메모리 모드에만),
 *       Redis 가 없을 때 구독 대기 때문에 앱 기동이 실패하고, 구독 실패 로그에 호스트가, 이벤트 처리 경고에 세션 ID 가 찍힌다.
 *       만료는 읽을 때 만료 시각으로 판단하고, 남은 키는 Redis TTL(만료 + 5분)로 지워진다.</li>
 *   <li>로그아웃하면 세션 해시까지 바로 지운다({@link RedisSessionLogoutHandler}).</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "aml.session.redis.enabled", havingValue = "true")
@Import(RedisAutoConfiguration.class)
public class RedisSessionConfig {

    /** Spring Session(indexed)이 만드는 키 공간 이벤트 구독 컨테이너의 빈 이름. 이것만 비운다. */
    static final String SESSION_LISTENER_CONTAINER = "springSessionRedisMessageListenerContainer";

    @Bean
    public SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        return new SpringSessionBackedSessionRegistry<>(sessions);
    }

    /**
     * Spring Session 이 만드는 키 공간 이벤트 구독 컨테이너를 구독 없는 빈 컨테이너로 바꾼다(위 이유).
     * 구독할 주제가 없으면 컨테이너는 시작해도 Redis 에 붙지 않는다. 시작 끄기 설정은 컨테이너에 없다.
     * 빈 이름으로 Spring Session 것만 바꾼다 — 나중에 다른 Redis 구독을 더해도 그건 그대로 받는다.
     */
    @Bean
    static BeanPostProcessor sessionEventSubscriptionOff() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (SESSION_LISTENER_CONTAINER.equals(beanName)
                        && bean instanceof RedisMessageListenerContainer subscribing) {
                    RedisMessageListenerContainer idle = new RedisMessageListenerContainer();
                    idle.setConnectionFactory(subscribing.getConnectionFactory());
                    idle.setBeanName(beanName);
                    return idle;
                }
                return bean;
            }
        };
    }

    @Bean
    RedisSessionLogoutHandler redisSessionLogoutHandler(StringRedisTemplate redis,
                                                        @Value("${spring.session.redis.namespace}") String namespace) {
        return new RedisSessionLogoutHandler(redis, namespace);
    }
}
