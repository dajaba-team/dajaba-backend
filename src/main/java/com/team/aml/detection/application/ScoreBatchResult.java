package com.team.aml.detection.application;

import com.team.aml.shared.batch.BatchRunView;

/** 점수 배치 결과 — 끝난 배치 행 + 건수. */
public record ScoreBatchResult(BatchRunView run, ScoreBatchSummary summary) {}
