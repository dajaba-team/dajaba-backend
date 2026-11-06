package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertForDraft;
import com.team.aml.detection.domain.AlertNotFoundException;
import com.team.aml.detection.domain.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * str 초안 요청이 부른다 (SD 명세 §10 GetAlertPayload, SCRUM-155). 경보 원문 + reasons + prior_alert_count.
 * str 은 detection 테이블을 직접 보지 않는다 — 의존 방향 str → detection.
 */
@Service
public class GetAlertForDraft {

    private final AlertRepository alerts;

    public GetAlertForDraft(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /** 없는 경보면 404 ALERT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public AlertForDraft execute(long alertId) {
        return alerts.findForDraft(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
    }
}
