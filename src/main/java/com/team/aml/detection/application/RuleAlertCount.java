package com.team.aml.detection.application;

/** 룰 한 버전이 이번 스캔에서 만든 경보 수 — 응답 alerts_by_rule 한 줄. */
public record RuleAlertCount(String ruleId, int ruleVersion, int alertCount) {
}
