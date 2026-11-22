package com.team.aml.shared.batch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.PostUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 배치 단계 공통 지표 (운영 도구 2절 준혁, SCRUM-349). {@link BatchRun}의 JPA 콜백 — 적재·룰 스캔·점수·케이스 생성의 기록 코드를
 * 고치지 않고, 행이 SUCCEEDED·FAILED 로 바뀌어 <b>커밋된 뒤</b> 한 번 센다(롤백되면 안 셈).
 * <ul>
 *   <li>{@code aml.batch.duration{type, result}} — started_at ~ finished_at</li>
 *   <li>{@code aml.batch.rows{type, kind}} — 성공한 실행의 rows_in(kind=in)·rows_out(kind=out)</li>
 * </ul>
 * 태그에 날짜·배치 번호 없음(03_shared §13). 세다 실패해도 배치는 그대로(fail-open).
 * 정해진 조합(유형 4 × 결과 2, 유형 4 × in·out)은 앱이 뜰 때 0으로 등록한다 — 처음 생길 때 값이 이미 1이면 Prometheus
 * {@code increase()}가 그 첫 실행을 0으로 봐서, 한 번만 도는 원천 적재가 대시보드에 안 나온다.
 * 앱 재시작 때 SQL 로 한꺼번에 FAILED 로 바꾸는 정리(failAbandoned*)는 엔티티를 거치지 않아 세지 않는다.
 */
@Component
public class BatchRunMetrics {

    private static final Logger log = LoggerFactory.getLogger(BatchRunMetrics.class);

    private final MeterRegistry meters;

    public BatchRunMetrics(MeterRegistry meters) {
        this.meters = meters;
        try {
            for (BatchType type : BatchType.values()) {
                for (BatchStatus result : List.of(BatchStatus.SUCCEEDED, BatchStatus.FAILED)) {
                    timer(type, result);
                }
                rows(type, "in");
                rows(type, "out");
            }
        } catch (RuntimeException e) {
            log.warn("배치 지표 등록 실패 cause={}", e.getClass().getSimpleName());
        }
    }

    @PostUpdate
    void onUpdate(BatchRun run) {
        if (run.getStatus() == BatchStatus.RUNNING || run.getFinishedAt() == null) {
            return;
        }
        Finished finished = new Finished(run.getBatchType(), run.getStatus(),
                Duration.between(run.getStartedAt(), run.getFinishedAt()), run.getRowsIn(), run.getRowsOut());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record(finished);
                }
            });
        } else {
            record(finished);
        }
    }

    void record(Finished f) {
        try {
            timer(f.type(), f.status()).record(f.duration().isNegative() ? Duration.ZERO : f.duration());
            if (f.status() == BatchStatus.SUCCEEDED) {
                rows(f.type(), "in").increment(f.rowsIn() == null ? 0 : f.rowsIn());
                rows(f.type(), "out").increment(f.rowsOut() == null ? 0 : f.rowsOut());
            }
        } catch (RuntimeException e) {
            log.warn("배치 지표 기록 실패 cause={}", e.getClass().getSimpleName());
        }
    }

    private Timer timer(BatchType type, BatchStatus result) {
        return Timer.builder("aml.batch.duration").tag("type", type.name()).tag("result", result.name()).register(meters);
    }

    private Counter rows(BatchType type, String kind) {
        return meters.counter("aml.batch.rows", "type", type.name(), "kind", kind);
    }

    /** 커밋 뒤 셀 값 — 엔티티를 붙잡지 않게 미리 꺼내 둔다. */
    record Finished(BatchType type, BatchStatus status, Duration duration, Integer rowsIn, Integer rowsOut) {
    }
}
