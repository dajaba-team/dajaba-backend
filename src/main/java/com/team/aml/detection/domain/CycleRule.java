package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * R04_CYCLE 판정 (룰 v2, rules.md R04). 고리 찾기는 SQL(RuleScanRepository.forEachCycleGroup), 경보 묶기는 여기.
 * 고리 = t1(A→B) … tk(→A), 2 ≤ k ≤ max_hops. 앞 거래 수신 = 다음 거래 송신, 분 단위로 엄격히 증가, 중간 계좌는 서로 다르고 A 가 아님.
 * 대상 계좌 A = 첫 거래의 송신 계좌, E = A 로 돌아오는 거래의 분. 사건형(rules.md 1.1 R04 예외) — 1분 전 창과 비교하지 않고
 * (A, E) 마다 경보 1건, paths = 그 E 에 닫힌 A 의 고리 전부(거래 조합 단위). 창 [E+1분-H, E+1분) 은 고리의 모든 거래가 안에 있는지만 본다.
 * 지금 구현은 4홉까지 — max_hops 가 2~4 가 아니면 INVALID_RULE_PARAMS (조용히 덜 찾지 않는다).
 */
public record CycleRule(int windowHours, int maxHops) {

    public static final String RULE_ID = "R04_CYCLE";
    public static final int SUPPORTED_MAX_HOPS = 4;
    static final String WINDOW = "window_hours";
    static final String MAX_HOPS = "max_hops";

    public CycleRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (maxHops < 2 || maxHops > SUPPORTED_MAX_HOPS) {
            throw new InvalidRuleParamsException(RULE_ID, MAX_HOPS);
        }
    }

    /** rules.params 에서 둘 다 읽는다. 없거나 정수가 아니거나 범위 밖이면 INVALID_RULE_PARAMS — 기본값으로 돌지 않는다. */
    public static CycleRule from(JsonNode params) {
        return new CycleRule(RuleParams.requiredInt(params, RULE_ID, WINDOW),
                RuleParams.requiredInt(params, RULE_ID, MAX_HOPS));
    }

    /**
     * 한 대상 계좌의 그날 고리로 그날 경보를 만든다 — 닫힌 분 E 마다 1건, paths = 그 E 에 닫힌 고리(첫 거래 시각순).
     * 창 안에 앞서 닫힌 고리가 남아 있어도 억제하지 않는다. 이미 닫힌 고리는 자기 E 에만 속해 다시 경보가 되지 않는다.
     */
    public List<CycleAlert> alerts(List<CyclePath> today, LocalDate day) {
        return today.stream()
                .filter(c -> c.closeMinute().toLocalDate().equals(day))
                .collect(Collectors.groupingBy(CyclePath::closeMinute, TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new CycleAlert(e.getKey(), e.getValue().stream().sorted(CyclePath.ORDER).toList()))
                .toList();
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — 다른 룰과 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
