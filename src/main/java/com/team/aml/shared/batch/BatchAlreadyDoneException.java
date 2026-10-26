package com.team.aml.shared.batch;

import com.team.aml.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.Map;

/** 409 BATCH_ALREADY_DONE */
public class BatchAlreadyDoneException extends DomainException {
    public BatchAlreadyDoneException(BatchType type, LocalDate date) {
        super(type + " " + date + " 는 이미 성공한 배치입니다", Map.of("batch_type", type.name(), "batch_date", date.toString()));
    }
}
