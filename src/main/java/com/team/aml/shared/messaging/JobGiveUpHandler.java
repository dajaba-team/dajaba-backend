package com.team.aml.shared.messaging;

/**
 * 큐 재시도를 다 써서 메시지가 DLQ 로 가기 직전에 부르는 곳 (SCRUM-276, #130 리뷰). 큐마다 하나, 없어도 된다.
 * 작업 행(batch_runs 등)이 RUNNING 으로 남아 다음 요청을 막지 않게 FAILED 로 바꾸는 데 쓴다.
 * 여기서 예외가 나도 메시지는 그대로 DLQ 로 간다({@link JobMessageRecoverer}가 로그만 남김).
 *
 * @param <T> 그 큐의 메시지 record (예: {@link BatchRunMessage})
 */
public interface JobGiveUpHandler<T> {

    /** {@link JobQueues}의 큐 이름. */
    String queue();

    /** 메시지 본문(JSON)을 읽을 타입. */
    Class<T> messageType();

    void onGiveUp(T message);
}
