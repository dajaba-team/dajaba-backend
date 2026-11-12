package com.team.aml.detection.domain;

import com.team.aml.shared.domain.NotFoundException;

import java.util.Map;

/** 404 ALERT_NOT_FOUND. */
public class AlertNotFoundException extends NotFoundException {

    public AlertNotFoundException(long alertId) {
        super("경보가 없습니다", Map.of("alert_id", alertId));
    }

    /** 화면 번호로 찾을 때 (관리자 경보 점검 R2·R3, SCRUM-198) — details 에 DB id 를 넣지 않는다. */
    public AlertNotFoundException(String alertNo) {
        super("경보가 없습니다", Map.of("alert_no", alertNo));
    }
}
