package com.team.aml.shared.messaging;

/**
 * batch_runs 행 하나를 처리하라는 메시지. JSON {@code {"batch_run_id": 123}} — 작업 ID만, 날짜·대상·상태는 DB에서.
 * 배치 단계(적재·룰 스캔·점수·케이스 생성)가 같이 쓴다. STR 처럼 다른 작업 ID면 자기 기능에 같은 모양의 record 를 만든다.
 */
public record BatchRunMessage(long batchRunId) {
}
