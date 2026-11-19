package com.team.aml.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재시도를 다 쓴 메시지 처리 (SCRUM-276, #130 리뷰). 기본(RejectAndDontRequeueRecoverer)처럼 재큐 없이 거절 → {@code <큐>.dlq}
 * 인데, 그 전에 그 큐의 {@link JobGiveUpHandler}를 한 번 부른다. Spring Boot 가 이 빈을 리스너 재시도에 끼운다.
 * 로그에는 큐 이름·예외 종류만 — 메시지 내용은 남기지 않는다(NFR-COM-04).
 */
class JobMessageRecoverer extends RejectAndDontRequeueRecoverer {

    private static final Logger log = LoggerFactory.getLogger(JobMessageRecoverer.class);

    private final Map<String, JobGiveUpHandler<?>> handlers;
    private final ObjectMapper objectMapper;

    JobMessageRecoverer(List<JobGiveUpHandler<?>> handlers, ObjectMapper objectMapper) {
        this.handlers = handlers.stream().collect(Collectors.toMap(JobGiveUpHandler::queue, Function.identity()));
        this.objectMapper = objectMapper;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String queue = message.getMessageProperties().getConsumerQueue();
        JobGiveUpHandler<?> handler = queue == null ? null : handlers.get(queue);
        if (handler != null) {
            try {
                giveUp(handler, message);
            } catch (Exception e) {
                // 형식이 틀린 메시지(읽기 실패)이거나 DB 가 계속 안 되는 경우 — 메시지는 그래도 DLQ 로
                log.error("작업 포기 처리 실패 queue={} cause={}", queue, e.getClass().getSimpleName());
            }
        }
        log.warn("작업 메시지 DLQ 로 queue={} cause={}", queue,
                cause == null ? null : rootCause(cause).getClass().getSimpleName());
        super.recover(message, cause);   // AmqpRejectAndDontRequeueException(을 감싼 예외) → 재큐 없이 거절 → DLX
    }

    private <T> void giveUp(JobGiveUpHandler<T> handler, Message message) throws java.io.IOException {
        handler.onGiveUp(objectMapper.readValue(message.getBody(), handler.messageType()));
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }
}
