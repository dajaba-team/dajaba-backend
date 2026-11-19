package com.team.aml.shared.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * application.yml {@code aml.messaging.*} (SCRUM-276). 연결 정보(호스트·계정)는 Spring 표준 {@code spring.rabbitmq.*}.
 *
 * @param enabled        RabbitMQ 켜기. 환경변수 {@code AML_RABBITMQ_ENABLED}, 기본 false
 * @param confirmTimeout 보낸 뒤 브로커 확인을 기다리는 시간. 넘으면 JobPublisher false → 동기 경로
 */
@ConfigurationProperties(prefix = "aml.messaging")
public record MessagingProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue("3s") Duration confirmTimeout) {
}
