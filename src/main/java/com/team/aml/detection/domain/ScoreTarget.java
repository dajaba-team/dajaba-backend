package com.team.aml.detection.domain;

import com.team.aml.shared.ai.AiDtos.AlertPayload;

/** 점수 요청 대상 경보 하나 — DB id + 규약 §2 경보 JSON(alert_id = alert_no). */
public record ScoreTarget(long alertId, AlertPayload payload) {}
