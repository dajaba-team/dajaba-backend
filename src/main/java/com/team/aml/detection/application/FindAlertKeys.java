package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertKey;
import com.team.aml.detection.domain.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 경보 id 여럿 → 경보 번호·배치 날짜 공개 조회 (SCRUM-420). 의존 방향 cases → detection.
 * 케이스 목록 한 페이지의 경보를 쿼리 하나로 찾는다 — ML+DL 조사 순위를 날짜별로 한 번씩 부르기 위해.
 */
@Service
public class FindAlertKeys {

    private final AlertRepository alerts;

    public FindAlertKeys(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /** @return 경보 id → 키. 없는 id 는 빠진다. 비면 쿼리 없이 빈 맵 */
    @Transactional(readOnly = true)
    public Map<Long, AlertKey> execute(Collection<Long> alertIds) {
        Set<Long> ids = alertIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return alerts.findKeys(ids).stream().collect(Collectors.toMap(AlertKey::alertId, Function.identity()));
    }
}
