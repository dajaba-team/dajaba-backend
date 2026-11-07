package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.detection.domain.AlertSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 케이스 상세용 경보·점수 공개 조회 (SCRUM-152). 의존 방향 cases → detection. */
@Service
public class GetAlertSummary {

    private final AlertRepository alerts;

    public GetAlertSummary(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /** @throws AlertNotFoundException 없는 경보 */
    @Transactional(readOnly = true)
    public AlertSummary execute(long alertId) {
        return alerts.findSummary(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
    }
}
