package com.team.aml.shared.messaging;

/**
 * 큐로 못 보낸 작업을 앱 안에서 돌려 달라는 이벤트 (SCRUM-349) — RabbitMQ 가 꺼져 있거나 브로커가 받지 않았을 때(JobPublisher false).
 * 보내는 쪽은 Spring ApplicationEventPublisher 로 이 이벤트를 내고, 그 큐를 받는 기능이 {@code @EventListener} 로 받아
 * 앱 작업 실행기에서 큐 리스너와 같은 일을 한다. 기능끼리 서로 부르지 않게(detection → cases) shared 에 둔다.
 *
 * @param queue      원래 보내려던 큐 이름(JobQueues) — 받는 쪽이 자기 큐인지 고른다
 * @param batchRunId 메시지와 같은 값(앞 단계 batch_run_id)
 */
public record InAppJob(String queue, long batchRunId) {
}
