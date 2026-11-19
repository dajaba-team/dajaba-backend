package com.team.aml.shared.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 브로커 확인(publisher confirm)까지 기다려서 "정말 받았나"를 돌려준다. 큐가 없어 라우팅이 안 되면(mandatory 반송) false.
 * 로그에는 큐 이름·예외 종류만 — 메시지 내용은 남기지 않는다(NFR-COM-04).
 *
 * <p>확인이 시간 안에 안 오면 false 지만 브로커가 실제로는 받았을 수 있다. 그래서 받는 쪽은 같은 작업이 두 번 와도
 * 안전해야 한다(케이스는 alert_id UNIQUE, 상태가 RUNNING 이 아니면 건너뜀).
 */
class RabbitJobPublisher implements JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitJobPublisher.class);

    private final RabbitTemplate template;
    private final Duration confirmTimeout;

    RabbitJobPublisher(RabbitTemplate template, Duration confirmTimeout) {
        this.template = template;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public boolean publish(String queue, Object message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.convertAndSend(JobQueues.EXCHANGE, queue, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                log.warn("작업 메시지 거절됨 queue={}", queue);
                return false;
            }
            if (correlation.getReturned() != null) {
                log.warn("작업 메시지 반송됨(큐 없음) queue={}", queue);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("작업 메시지 보내기 중단 queue={}", queue);
            return false;
        } catch (Exception e) {
            log.warn("작업 메시지 보내기 실패 queue={} cause={}", queue, e.getClass().getSimpleName());
            return false;
        }
    }
}
