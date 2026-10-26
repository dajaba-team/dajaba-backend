package com.team.aml.shared.batch;

/** batch_runs.batch_type (V004). 10/27 안건 21 결과에 따라 SCORE 재실행 규칙이 바뀔 수 있다. */
public enum BatchType { LOAD_DATASET, RULE_SCAN, SCORE_REQUEST, OPEN_CASES }
