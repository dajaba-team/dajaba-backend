package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 룰 스캔이 저장할 경보 한 건. alert_no 는 저장할 때 시퀀스로, score_status 는 PENDING 기본값. */
public record NewAlert(long batchRunId, LocalDate batchDate, long ruleRefId, String accountKey, String entityId,
                       LocalDateTime windowStart, LocalDateTime windowEnd, LocalDateTime referenceTs,
                       JsonNode ruleFacts, List<Long> transactionIds) {
    public NewAlert {
        transactionIds = List.copyOf(transactionIds);
    }
}
