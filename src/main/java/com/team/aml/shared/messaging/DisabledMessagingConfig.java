package com.team.aml.shared.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 를 끈 상태(기본값). {@link JobPublisher}는 항상 false — 부르는 쪽이 동기 경로로. */
@Configuration
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMessagingConfig {

    @Bean
    JobPublisher disabledJobPublisher() {
        return new DisabledJobPublisher();
    }
}
