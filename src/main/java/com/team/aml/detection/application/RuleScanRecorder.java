package com.team.aml.detection.application;

import com.team.aml.detection.domain.DataRange;
import com.team.aml.detection.domain.DatasetNotLoadedException;
import com.team.aml.detection.domain.DateOutOfRangeException;
import com.team.aml.detection.domain.NoActiveRulesException;
import com.team.aml.detection.domain.RuleScanRepository;
import com.team.aml.shared.batch.BatchAlreadyDoneException;
import com.team.aml.shared.batch.BatchRun;
import com.team.aml.shared.batch.BatchRunRepository;
import com.team.aml.shared.batch.BatchRunningException;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 룰 스캔의 batch_runs 기록 (SD 명세 §5 처리 1·6). 적재의 DatasetLoadRecorder 와 같은 모양.
 * 시작·실패는 따로 커밋(실패는 스캔이 롤백돼도 남는다), 성공은 스캔 트랜잭션 안에서 경보와 같이 커밋.
 */
@Service
public class RuleScanRecorder {

    private static final Logger log = LoggerFactory.getLogger(RuleScanRecorder.class);
    static final String ABANDONED_MESSAGE = "앱 재시작으로 중단된 룰 스캔";

    private final BatchRunRepository batchRuns;
    private final RuleScanRepository scans;

    public RuleScanRecorder(BatchRunRepository batchRuns, RuleScanRepository scans) {
        this.batchRuns = batchRuns;
        this.scans = scans;
    }

    /** 거절 사유가 있으면 409 를 던지고 아무것도 남기지 않는다. 통과하면 RUNNING 행을 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(LocalDate date, long triggeredBy) {
        scans.lockScanStart();
        DataRange range = scans.dataRange().orElseThrow(DatasetNotLoadedException::new);
        if (!range.contains(date)) {
            throw new DateOutOfRangeException(date, range);
        }
        if (batchRuns.findFirstByBatchTypeAndBatchDateAndStatus(BatchType.RULE_SCAN, date, BatchStatus.RUNNING)
                .isPresent()) {
            throw new BatchRunningException(BatchType.RULE_SCAN, date);
        }
        if (batchRuns.existsByBatchTypeAndBatchDateAndStatus(BatchType.RULE_SCAN, date, BatchStatus.SUCCEEDED)) {
            throw new BatchAlreadyDoneException(BatchType.RULE_SCAN, date);
        }
        if (scans.findActiveRules().isEmpty()) {
            throw new NoActiveRulesException();
        }
        return batchRuns.save(BatchRun.start(BatchType.RULE_SCAN, date, triggeredBy)).getId();
    }

    /** 스캔 트랜잭션 안에서만. 경보는 들어갔는데 상태가 RUNNING 으로 남는 일을 막는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeed(long batchRunId, int rowsIn, int rowsOut) {
        batchRuns.findById(batchRunId).orElseThrow().succeed(rowsIn, rowsOut);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long batchRunId, String message) {
        batchRuns.findById(batchRunId).orElseThrow().fail(message);
    }

    /** 룰 스캔은 동기라 앱이 죽으면 같이 끊긴다. 기동 직후 남은 RUNNING 은 FAILED 로 — 그 날짜를 다시 돌릴 수 있게. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failAbandonedScans() {
        int cleaned = scans.failRunningScans(ABANDONED_MESSAGE);
        if (cleaned > 0) {
            log.warn("중단된 룰 스캔 {}건을 FAILED로 정리", cleaned);
        }
    }
}
