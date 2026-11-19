package com.team.aml.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 구독 끄기는 Spring Session 컨테이너만 — 다른 Redis 구독은 그대로 (#143 리뷰, SCRUM-273). */
class RedisSessionConfigTest {

    private final BeanPostProcessor off = RedisSessionConfig.sessionEventSubscriptionOff();
    private final RedisConnectionFactory connections = mock(RedisConnectionFactory.class);

    @Test
    void 정상_Spring_Session_컨테이너는_구독_없는_새_컨테이너로_바뀐다() {
        RedisMessageListenerContainer subscribing = withListener();

        Object result = off.postProcessBeforeInitialization(subscribing, RedisSessionConfig.SESSION_LISTENER_CONTAINER);

        assertThat(result).isNotSameAs(subscribing).isInstanceOf(RedisMessageListenerContainer.class);
        assertThat(((RedisMessageListenerContainer) result).getConnectionFactory()).isSameAs(connections);
    }

    @Test
    void 경계_다른_이름의_구독_컨테이너는_그대로_둔다() {
        RedisMessageListenerContainer other = withListener();

        assertThat(off.postProcessBeforeInitialization(other, "caseEventListenerContainer")).isSameAs(other);
    }

    private RedisMessageListenerContainer withListener() {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(connections);
        c.addMessageListener((message, pattern) -> { }, new ChannelTopic("t"));
        return c;
    }
}
