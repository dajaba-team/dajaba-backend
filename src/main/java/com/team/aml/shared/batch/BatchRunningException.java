package com.team.aml.shared.batch;

import com.team.aml.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.Map;

/** 409 BATCH_RUNNING */
public class BatchRunningException extends DomainException {
    public BatchRunningException(BatchType type, LocalDate date) {
        super(type + " " + date + " 가 실행 중입니다", Map.of("batch_type", type.name(), "batch_date", date.toString()));
    }
}
