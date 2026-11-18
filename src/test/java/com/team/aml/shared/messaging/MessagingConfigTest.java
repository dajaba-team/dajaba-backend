package com.team.aml.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 브로커 없이 — 켜기/끄기 스위치와 큐·DLQ 선언 모양 (SCRUM-276). 연결은 처음 쓸 때 열리므로 컨텍스트는 브로커 없이 뜬다. */
class MessagingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
            .withUserConfiguration(RabbitMessagingConfig.class, DisabledMessagingConfig.class);

    @Test
    void 정상_기본값은_꺼짐_항상_false_큐_선언_없음() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(JobPublisher.class)).isInstanceOf(DisabledJobPublisher.class);
            assertThat(ctx.getBean(JobPublisher.class).publish(JobQueues.CASE_OPEN_CASES, new BatchRunMessage(1))).isFalse();
            assertThat(ctx).doesNotHaveBean(Declarables.class);
        });
    }

    @Test
    void 정상_켜면_큐마다_DLQ를_달아_선언한다() {
        runner.withPropertyValues("aml.messaging.enabled=true").run(ctx -> {
            assertThat(ctx.getBean(JobPublisher.class)).isInstanceOf(RabbitJobPublisher.class);
            assertThat(ctx.getBean(MessageRecoverer.class)).isInstanceOf(JobMessageRecoverer.class);
            Declarables topology = ctx.getBean(Declarables.class);
            for (String name : JobQueues.ALL) {
                Queue queue = topology.getDeclarablesByType(Queue.class).stream()
                        .filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
                assertThat(queue.isDurable()).isTrue();
                assertThat(queue.getArguments())
                        .containsEntry("x-dead-letter-exchange", JobQueues.DEAD_LETTER_EXCHANGE)
                        .containsEntry("x-dead-letter-routing-key", name + ".dlq");
                assertThat(topology.getDeclarablesByType(Queue.class)).anyMatch(q -> q.getName().equals(name + ".dlq"));
            }
        });
    }

    @Test
    void 정상_메시지는_작업_ID만_snake_case_JSON() {
        runner.withPropertyValues("aml.messaging.enabled=true").run(ctx -> {
            MessageConverter converter = ctx.getBean(MessageConverter.class);
            String json = new String(converter.toMessage(new BatchRunMessage(42), new MessageProperties()).getBody(),
                    StandardCharsets.UTF_8);
            assertThat(json).isEqualTo("{\"batch_run_id\":42}");
        });
    }

    @Test
    void 정상_큐_이름은_규칙을_따른다() {
        assertThat(JobQueues.ALL).allMatch(q -> q.startsWith("batch.") || q.startsWith("case.") || q.startsWith("str.pdf."));
    }
}
