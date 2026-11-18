package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * R04_CYCLE 판정 (룰 v2, rules.md R04). 고리 찾기는 SQL(RuleScanRepository.cyclesClosing), 거짓→참 판정은 여기.
 * 고리 = t1(A→B) … tk(→A), 2 ≤ k ≤ max_hops. 앞 거래 수신 = 다음 거래 송신, 분 단위로 엄격히 증가, 중간 계좌는 서로 다르고 A 가 아님.
 * 대상 계좌 A = 첫 거래의 송신 계좌, E = A 로 돌아오는 거래의 분. 창 [E+1분-H, E+1분) 에 A 의 고리가 있고
 * 1분 전 창 [E-H, E) 에는 없을 때만 경보 1건. 그 경보의 paths = E 시점 창 안의 A 고리 전부.
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
     * 한 대상 계좌의 그날 고리(today)와, 1분 전 창 판단용으로 그 전 H 안에 닫힌 고리 요약(earlier)으로 그날 경보를 만든다.
     * E 후보 = 그날 고리가 닫힌 분뿐 — 다른 분은 지금 창의 고리가 1분 전 창에도 있어 거짓→참이 될 수 없다.
     * 경보의 paths 는 today 에서만 — 전날 닫힌 고리가 지금 창에 남아 있으면 1분 전 창에도 있어 그 E 는 경보가 아니다.
     */
    public List<CycleAlert> alerts(List<CyclePath> today, List<CycleMark> earlier, LocalDate day) {
        List<CycleMark> marks = Stream.concat(today.stream().map(CyclePath::mark), earlier.stream()).toList();
        TreeSet<LocalDateTime> closings = today.stream().map(CyclePath::closeMinute)
                .filter(m -> m.toLocalDate().equals(day))
                .collect(Collectors.toCollection(TreeSet::new));
        List<CycleAlert> alerts = new ArrayList<>();
        for (LocalDateTime e : closings) {
            LocalDateTime beforeStart = e.minusHours(windowHours);
            boolean before = marks.stream()
                    .anyMatch(m -> m.closeMinute().isBefore(e) && !m.latestFirstTs().isBefore(beforeStart));
            if (before) {
                continue;
            }
            LocalDateTime start = windowStart(e);
            List<CyclePath> now = today.stream()
                    .filter(c -> !c.closeMinute().isAfter(e) && !c.firstTs().isBefore(start))
                    .sorted(CyclePath.ORDER).toList();
            alerts.add(new CycleAlert(e, now));
        }
        return alerts;
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — 다른 룰과 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
