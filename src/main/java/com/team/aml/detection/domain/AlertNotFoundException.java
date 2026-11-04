package com.team.aml.detection.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 ALERT_NOT_FOUND. */
public class AlertNotFoundException extends NotFoundException {

    public AlertNotFoundException(long alertId) {
        super("경보가 없습니다", Map.of("alert_id", alertId));
    }
}
