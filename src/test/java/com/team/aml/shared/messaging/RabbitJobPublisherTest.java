package com.team.aml.shared.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 브로커 없이 — 확인(confirm)·반송·연결 실패를 true/false 로 바꾸는지 (SCRUM-276). */
class RabbitJobPublisherTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitJobPublisher publisher = new RabbitJobPublisher(template, Duration.ofMillis(200));
    private final BatchRunMessage message = new BatchRunMessage(1L);

    @Test
    void 정상_브로커가_확인하면_true() {
        answer((c) -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, message)).isTrue();
    }

    @Test
    void 실패_브로커가_거절하면_false() {
        answer((c) -> c.getFuture().complete(new CorrelationData.Confirm(false, "nack")));
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, message)).isFalse();
    }

    @Test
    void 실패_큐가_없어_반송되면_false() {
        answer((c) -> {
            c.setReturned(new ReturnedMessage(new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE",
                    JobQueues.EXCHANGE, JobQueues.CASE_OPEN_CASES));
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, message)).isFalse();
    }

    @Test
    void 경계_확인이_시간_안에_안_오면_false() {
        answer((c) -> { });
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, message)).isFalse();
    }

    @Test
    void 실패_연결이_안_되면_예외_없이_false() {
        doThrow(new AmqpConnectException(new ConnectException("refused")))
                .when(template).convertAndSend(eq(JobQueues.EXCHANGE), eq(JobQueues.CASE_OPEN_CASES), any(Object.class),
                        any(CorrelationData.class));
        assertThat(publisher.publish(JobQueues.CASE_OPEN_CASES, message)).isFalse();
    }

    private void answer(java.util.function.Consumer<CorrelationData> broker) {
        doAnswer(inv -> {
            broker.accept(inv.getArgument(3));
            return null;
        }).when(template).convertAndSend(eq(JobQueues.EXCHANGE), eq(JobQueues.CASE_OPEN_CASES), any(Object.class),
                any(CorrelationData.class));
    }
}
