package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertRepository;
import com.team.aml.detection.domain.PriorAlert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 같은 법인의 이전 경보 공개 조회 (SD 명세 §10 FindAlertsByEntity, SCRUM-239). 의존 방향 cases → detection.
 * 기준은 GetAlertForDraft 의 prior_alert_count 와 같은 조건(같은 쿼리 조각) — 같은 entity_id 이고 reference_ts 가
 * before 보다 앞선 경보. 법인을 모르면(entity_id null) 계좌로 임의 연결하지 않고 빈 목록.
 */
@Service
public class FindAlertsByEntity {

    private final AlertRepository alerts;

    public FindAlertsByEntity(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * @param limit 최대 건수(1 이상). 최근(reference_ts 내림차순, 같으면 id 내림차순) 것부터
     * @return 법인·기준 시각이 없으면 빈 목록
     */
    @Transactional(readOnly = true)
    public List<PriorAlert> execute(String entityId, LocalDateTime before, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상: " + limit);
        }
        if (entityId == null || before == null) {
            return List.of();
        }
        return alerts.findPriorByEntity(entityId, before, limit);
    }
}
