package com.team.aml.detection.api;

import com.team.aml.detection.application.RuleAlertCount;
import com.team.aml.detection.application.RuleScanResult;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** SD 명세 §5 응답. rows_in = 그날 거래 수, rows_out = 만든 경보 수. */
public record RuleScanResponse(long batchRunId, BatchType batchType, LocalDate batchDate, BatchStatus status,
                               Integer rowsIn, Integer rowsOut, List<RuleAlertCount> alertsByRule,
                               Instant startedAt, Instant finishedAt) {

    static RuleScanResponse from(RuleScanResult r) {
        BatchRunView run = r.run();
        return new RuleScanResponse(run.batchRunId(), run.batchType(), run.batchDate(), run.status(),
                run.rowsIn(), run.rowsOut(), r.alertsByRule(), run.startedAt(), run.finishedAt());
    }
}
