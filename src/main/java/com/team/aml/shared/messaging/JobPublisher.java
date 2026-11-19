package com.team.aml.shared.messaging;

/**
 * 작업 메시지 보내기 (SCRUM-276). 예외를 던지지 않는다.
 * false 면 브로커가 메시지를 받지 않은 것 — 꺼짐({@code AML_RABBITMQ_ENABLED=false})·연결 실패·확인(confirm) 거절·큐 없음.
 * 부르는 쪽은 false 일 때 기존 동기 경로로 돌린다(fail-open).
 */
public interface JobPublisher {

    /**
     * @param queue   {@link JobQueues}의 큐 이름(= 라우팅 키)
     * @param message 작업 ID만 담은 record (예: {@link BatchRunMessage})
     * @return 브로커가 받았으면 true
     */
    boolean publish(String queue, Object message);
}
