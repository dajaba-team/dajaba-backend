package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.Map;

/** 409 RULE_SCAN_NOT_DONE — 그 날짜 룰 스캔이 아직 성공하지 않아 점수 낼 경보가 다 만들어지지 않았다 (SD 명세 §2.3). */
public class RuleScanNotDoneException extends DomainException {
    public RuleScanNotDoneException(LocalDate date) {
        super(date + " 룰 스캔이 아직 성공하지 않았습니다", Map.of("batch_date", date.toString()));
    }
}
