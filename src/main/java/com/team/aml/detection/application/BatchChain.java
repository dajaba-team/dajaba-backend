package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.messaging.BatchRunMessage;
import com.team.aml.shared.messaging.InAppJob;
import com.team.aml.shared.messaging.JobPublisher;
import com.team.aml.shared.messaging.JobQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 배치 자동 연쇄 (운영 도구 2절 준혁, SCRUM-349) — 룰 스캔 성공 → 같은 날짜 점수 → 점수 SUCCEEDED → 같은 날짜 케이스 생성.
 * 원천 적재는 날짜가 없는 최초 1회라 잇지 않는다. 메시지에는 앞 단계 batch_run_id 만 — 날짜·요청자는 받는 쪽이 batch_runs 에서 읽는다.
 * 화면에서 직접 실행한 단계도 성공하면 이어진다(연쇄는 "앞 단계 성공 뒤"에 걸려 있음).
 *
 * <p><b>항상 잇는다.</b> RabbitMQ 가 켜져 있으면 큐(batch.score·batch.open-cases)로 보내고, 꺼져 있거나 브로커가 받지 않으면
 * (JobPublisher false) {@link InAppJob} 이벤트로 앱 안에서 같은 일을 돌린다 — 케이스 생성(RequestOpenCases)의 "큐, 안 되면 앱 실행기"와 같다.
 * 확인(confirm)이 늦어 false 였는데 브로커가 실제로 받았어도 받는 쪽이 시작 잠금 안에서 "그 뒤 이미 시작됨"을 보고 한 번만 돈다.
 * 예외를 던지지 않는다 — 앞 단계 결과에 영향 없음.
 *
 * <p>{@code aml.batch.chain-enabled} 는 운영 설정이 아니다(환경 변수 없음). <b>테스트 프로필만</b> false — 단계별 테스트가 뒤에서
 * 이어 도는 다음 단계에 흔들리지 않게. 연쇄를 보는 테스트는 직접 켠다.
 */
@Service
public class BatchChain {

    private static final Logger log = LoggerFactory.getLogger(BatchChain.class);

    private final JobPublisher publisher;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public BatchChain(JobPublisher publisher, ApplicationEventPublisher events,
                      @Value("${aml.batch.chain-enabled:true}") boolean enabled) {
        this.publisher = publisher;
        this.events = events;
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
            return;
        }
        try {
            events.publishEvent(new InAppJob(queue, batchRunId));
            log.info("배치 잇기 {} 앱 안에서 queue={} batch_run_id={}", next, queue, batchRunId);
        } catch (RuntimeException e) {   // 받는 쪽이 실행기에 못 넘긴 경우 등 — 앞 단계 결과는 그대로, 다음 단계는 직접 실행
            log.error("배치 잇기 못 함 queue={} batch_run_id={} cause={}", queue, batchRunId, e.getClass().getSimpleName());
        }
    }
}
