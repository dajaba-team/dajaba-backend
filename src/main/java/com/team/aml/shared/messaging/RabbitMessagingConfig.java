package com.team.aml.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ 공통 설정 (SCRUM-276). {@code aml.messaging.enabled=true}({@code AML_RABBITMQ_ENABLED})일 때만.
 * 꺼져 있으면 브로커에 연결 자체를 하지 않는다 — 이 클래스와 {@code @RabbitListener} 빈이 안 만들어지므로.
 *
 * <p>ACK·재시도·DLQ 기본값(application.yml {@code spring.rabbitmq.listener.simple}) — 진희 공통 정책 확정 전 초안:
 * 처리 메서드가 정상 반환하면 ACK, 예외면 1s·2s 간격으로 총 3번 시도 후 재큐 없이 거절 → {@code <큐>.dlq}.
 * 메시지 형식이 틀리면(변환 실패) 재시도 없이 바로 DLQ.
 */
@Configuration
@ConditionalOnProperty(name = "aml.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitMessagingConfig {

    /** 앱 ObjectMapper(snake_case, 규약 0절) 그대로 — 메시지 JSON 도 {@code batch_run_id}. */
    @Bean
    MessageConverter jobMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /** 교환기 2개 + 큐마다 (큐, DLQ, 바인딩 2개). 브로커에 처음 연결할 때 RabbitAdmin 이 선언한다(있으면 그대로). */
    @Bean
    Declarables jobTopology() {
        DirectExchange exchange = ExchangeBuilder.directExchange(JobQueues.EXCHANGE).durable(true).build();
        DirectExchange dlx = ExchangeBuilder.directExchange(JobQueues.DEAD_LETTER_EXCHANGE).durable(true).build();
        List<Declarable> all = new ArrayList<>(List.of(exchange, dlx));
        for (String name : JobQueues.ALL) {
            String dlqName = JobQueues.deadLetterQueue(name);
            Queue queue = QueueBuilder.durable(name)
                    .deadLetterExchange(JobQueues.DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(dlqName)
                    .build();
            Queue dlq = QueueBuilder.durable(dlqName).build();
            Binding bind = BindingBuilder.bind(queue).to(exchange).with(name);
            Binding bindDlq = BindingBuilder.bind(dlq).to(dlx).with(dlqName);
            all.addAll(List.of(queue, dlq, bind, bindDlq));
        }
        return new Declarables(all);
    }

    @Bean
    JobPublisher rabbitJobPublisher(RabbitTemplate template, MessagingProperties properties) {
        return new RabbitJobPublisher(template, properties.confirmTimeout());
    }
}
