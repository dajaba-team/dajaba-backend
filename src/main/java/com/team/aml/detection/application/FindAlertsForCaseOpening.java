package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertForCaseOpening;
import com.team.aml.detection.domain.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * cases 케이스 생성 배치가 부른다 (SD 명세 §10, CASES 명세 §6.1). 그 날짜의 SCORED·미생성 경보와 점수.
 * HIGH 로 미리 거르지 않는다 — 등급은 CasePolicy 가 정한다 (안건 2). FAILED·PENDING 경보는 대상이 아니다 (안건 4).
 */
@Service
public class FindAlertsForCaseOpening {

    private final AlertRepository alerts;

    public FindAlertsForCaseOpening(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public List<AlertForCaseOpening> execute(LocalDate batchDate) {
        return alerts.findForCaseOpening(batchDate);
    }
}
