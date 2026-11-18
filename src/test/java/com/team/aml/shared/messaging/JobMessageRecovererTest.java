package com.team.aml.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 브로커 없이 — 재시도를 다 쓴 메시지: 그 큐의 포기 처리를 부르고, 무슨 일이 있어도 DLQ 로 (#130 리뷰). */
class JobMessageRecovererTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final List<Long> gaveUp = new ArrayList<>();

    private final JobGiveUpHandler<BatchRunMessage> handler = new JobGiveUpHandler<>() {
        public String queue() { return JobQueues.CASE_OPEN_CASES; }
        public Class<BatchRunMessage> messageType() { return BatchRunMessage.class; }
        public void onGiveUp(BatchRunMessage message) { gaveUp.add(message.batchRunId()); }
    };

    @Test
    void 정상_그_큐의_포기_처리를_batch_run_id로_부르고_DLQ로_거절한다() {
        JobMessageRecoverer recoverer = new JobMessageRecoverer(List.of(handler), objectMapper);

        assertThatThrownBy(() -> recoverer.recover(message(JobQueues.CASE_OPEN_CASES, "{\"batch_run_id\": 42}"),
                new IllegalStateException("db down")))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(gaveUp).containsExactly(42L);
    }

    @Test
    void 경계_포기_처리가_없는_큐도_DLQ로_거절한다() {
        JobMessageRecoverer recoverer = new JobMessageRecoverer(List.of(handler), objectMapper);

        assertThatThrownBy(() -> recoverer.recover(message("batch.other", "{\"batch_run_id\": 42}"), new RuntimeException()))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(gaveUp).isEmpty();
    }

    @Test
    void 실패_본문을_못_읽거나_포기_처리가_터져도_DLQ로_거절한다() {
        JobGiveUpHandler<BatchRunMessage> exploding = new JobGiveUpHandler<>() {
            public String queue() { return JobQueues.CASE_OPEN_CASES; }
            public Class<BatchRunMessage> messageType() { return BatchRunMessage.class; }
            public void onGiveUp(BatchRunMessage message) { throw new IllegalStateException("db still down"); }
        };
        JobMessageRecoverer recoverer = new JobMessageRecoverer(List.of(exploding), objectMapper);

        assertThatThrownBy(() -> recoverer.recover(message(JobQueues.CASE_OPEN_CASES, "not-json"), new RuntimeException()))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> recoverer.recover(message(JobQueues.CASE_OPEN_CASES, "{\"batch_run_id\": 1}"),
                new RuntimeException()))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String consumerQueue, String body) {
        MessageProperties props = new MessageProperties();
        props.setConsumerQueue(consumerQueue);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
