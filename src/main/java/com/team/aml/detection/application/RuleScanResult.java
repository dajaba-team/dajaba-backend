package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchRunView;

import java.util.List;

/** 룰 스캔 결과 — 끝난 배치 행 + 룰별 경보 수. */
public record RuleScanResult(BatchRunView run, List<RuleAlertCount> alertsByRule) {
    public RuleScanResult {
        alertsByRule = List.copyOf(alertsByRule);
    }
}
