package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 배치 자동 연쇄 (운영 도구 2절 준혁, SCRUM-349) — 룰 스캔 성공 → 같은 날짜 점수 → 점수 SUCCEEDED → 같은 날짜 케이스 생성.
 * 원천 적재는 날짜가 없는 최초 1회라 잇지 않는다. 메시지에는 앞 단계 batch_run_id 만 — 날짜·요청자는 받는 쪽이 batch_runs 에서 읽는다.
 * 화면에서 직접 실행한 단계도 성공하면 이어진다(연쇄는 "앞 단계 성공 뒤"에 걸려 있음).
 *
 * <p>{@code aml.batch.chain-enabled}(AML_BATCH_CHAIN_ENABLED, 기본 false)가 꺼져 있거나 브로커가 받지 않으면(RabbitMQ 꺼짐 포함)
 * 로그만 남기고, 관리자가 다음 단계를 직접 실행한다(지금과 같음). 예외를 던지지 않는다 — 앞 단계 결과에 영향 없음.
 */
@Service
public class BatchChain {

    private static final Logger log = LoggerFactory.getLogger(BatchChain.class);

    private final JobPublisher publisher;
    private final boolean enabled;

    public BatchChain(JobPublisher publisher, @Value("${aml.batch.chain-enabled:false}") boolean enabled) {
        this.publisher = publisher;
        this.enabled = enabled;
    }

    /** 룰 스캔이 성공한 뒤 — 같은 날짜 점수로. */
    public void afterRuleScan(long ruleScanRunId) {
        send(JobQueues.BATCH_SCORE, ruleScanRunId, "점수");
    }

    /** 점수가 끝난 뒤 — SUCCEEDED 면 케이스 생성으로. 다시 보낼 실패가 남았으면(FAILED) 멈추고, 재처리해서 성공하면 그때 잇는다. */
    public void afterScore(long scoreRunId, BatchStatus status) {
        if (!enabled) {
            return;
        }
        if (status != BatchStatus.SUCCEEDED) {
            log.info("배치 잇기 멈춤 batch_run_id={} status={} — 점수 재처리가 성공하면 이어져요", scoreRunId, status);
            return;
        }
        send(JobQueues.BATCH_OPEN_CASES, scoreRunId, "케이스 생성");
    }

    private void send(String queue, long batchRunId, String next) {
        if (!enabled) {
            return;
        }
        if (publisher.publish(queue, new BatchRunMessage(batchRunId))) {
            log.info("배치 잇기 {} 큐에 넣음 queue={} batch_run_id={}", next, queue, batchRunId);
        } else {
            log.warn("배치 잇기 못 함 — {} 단계는 직접 실행해 주세요 queue={} batch_run_id={}", next, queue, batchRunId);
        }
    }
}
