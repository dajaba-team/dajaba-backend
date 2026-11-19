package com.team.aml.detection.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * R05_SCATTER_GATHER 판정 (룰 v2, rules.md R05). 후보 찾기는 SQL(RuleScanRepository.scatterGatherLegs), 판정은 여기.
 * 출발 S → 서로 다른 중간 계좌 Mᵢ → 도착 T. 각 Mᵢ 에서 S→Mᵢ 가 Mᵢ→T 보다 이른 분, 모든 거래가 창 안. S ≠ T, Mᵢ ∉ {S, T}.
 * 대상 T, E = 그날 Mᵢ→T 가 있는 분. 지금 창 [E+1분-H, E+1분) 에 Mᵢ 가 N 이상인 S 가 있고 1분 전 창 [E-H, E) 에는 없을 때만 경보(B7).
 * 근거: paths 는 Mᵢ 마다 대표 하나(가장 이른 S→Mᵢ + 그보다 늦은 분의 가장 이른 Mᵢ→T), transaction_ids 는 모든 유효 경로의 거래.
 */
public record ScatterGatherRule(int windowHours, int minIntermediates) {

    public static final String RULE_ID = "R05_SCATTER_GATHER";
    static final String WINDOW = "window_hours";
    static final String MIN_INTERMEDIATES = "min_intermediates";

    public ScatterGatherRule {
        if (windowHours < 1) {
            throw new InvalidRuleParamsException(RULE_ID, WINDOW);
        }
        if (minIntermediates < 1) {
            throw new InvalidRuleParamsException(RULE_ID, MIN_INTERMEDIATES);
        }
    }

    /** rules.params 에서 둘 다 읽는다. 없거나 정수가 아니거나 1 미만이면 INVALID_RULE_PARAMS — 기본값으로 돌지 않는다. */
    public static ScatterGatherRule from(JsonNode params) {
        return new ScatterGatherRule(RuleParams.requiredInt(params, RULE_ID, WINDOW),
                RuleParams.requiredInt(params, RULE_ID, MIN_INTERMEDIATES));
    }

    /**
     * 한 도착 계좌 T 의 다리(후보 S 전부, 그날 + 1분 전 창 판단용 그 전 H)로 그날 경보를 만든다.
     * E 후보 = 그날 M→T 다리가 있는 분. 출발 S 가 여럿이면 Mᵢ 가 가장 많은 S, 같으면 계좌 키 순으로 앞.
     */
    public List<ScatterGatherAlert> alerts(List<ScatterGatherLeg> legs, LocalDate day) {
        Map<String, List<ScatterGatherLeg>> bySource = legs.stream()
                .collect(Collectors.groupingBy(ScatterGatherLeg::source, TreeMap::new, Collectors.toList()));
        TreeSet<LocalDateTime> closings = legs.stream().filter(ScatterGatherLeg::toTarget)
                .map(ScatterGatherLeg::minute).filter(m -> m.toLocalDate().equals(day))
                .collect(Collectors.toCollection(TreeSet::new));
        List<ScatterGatherAlert> alerts = new ArrayList<>();
        for (LocalDateTime e : closings) {
            LocalDateTime beforeStart = e.minusHours(windowHours);
            boolean before = bySource.values().stream()
                    .anyMatch(ls -> mids(ls, beforeStart, e).size() >= minIntermediates);
            if (before) {
                continue;
            }
            LocalDateTime start = windowStart(e);
            LocalDateTime end = windowEnd(e);
            String best = null;
            Set<String> bestMids = Set.of();
            for (Map.Entry<String, List<ScatterGatherLeg>> s : bySource.entrySet()) {
                Set<String> mids = mids(s.getValue(), start, end);
                if (mids.size() >= minIntermediates && mids.size() > bestMids.size()) {
                    best = s.getKey();
                    bestMids = mids;
                }
            }
            if (best != null) {
                alerts.add(evidence(bySource.get(best), best, e, start, end, bestMids));
            }
        }
        return alerts;
    }

    /** [from, to) 안에서 S→M 이 M→T 보다 이른 분인 서로 다른 M (가장 이른 S→M < 가장 늦은 M→T). */
    static Set<String> mids(List<ScatterGatherLeg> legs, LocalDateTime from, LocalDateTime to) {
        Map<String, LocalDateTime> firstOut = new HashMap<>();
        Map<String, LocalDateTime> lastIn = new HashMap<>();
        for (ScatterGatherLeg l : legs) {
            if (l.ts().isBefore(from) || !l.ts().isBefore(to)) {
                continue;
            }
            if (l.toTarget()) {
                lastIn.merge(l.mid(), l.minute(), (x, y) -> x.isAfter(y) ? x : y);
            } else {
                firstOut.merge(l.mid(), l.minute(), (x, y) -> x.isBefore(y) ? x : y);
            }
        }
        return firstOut.keySet().stream()
                .filter(m -> lastIn.containsKey(m) && firstOut.get(m).isBefore(lastIn.get(m)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 고른 S 의 Mᵢ 마다 대표 경로 + 모든 유효 경로의 거래. 경로끼리는 첫 거래 시각순. */
    private ScatterGatherAlert evidence(List<ScatterGatherLeg> legs, String source, LocalDateTime e,
                                        LocalDateTime start, LocalDateTime end, Set<String> mids) {
        Comparator<ScatterGatherLeg> byTime = Comparator.comparing(ScatterGatherLeg::ts)
                .thenComparingLong(ScatterGatherLeg::id);
        List<List<ScatterGatherLeg>> paths = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (String m : mids) {
            List<ScatterGatherLeg> outs = legs.stream().filter(l -> !l.toTarget() && l.mid().equals(m))
                    .filter(l -> !l.ts().isBefore(start) && l.ts().isBefore(end)).sorted(byTime).toList();
            List<ScatterGatherLeg> ins = legs.stream().filter(l -> l.toTarget() && l.mid().equals(m))
                    .filter(l -> !l.ts().isBefore(start) && l.ts().isBefore(end)).sorted(byTime).toList();
            ScatterGatherLeg a = outs.get(0);
            ScatterGatherLeg b = ins.stream().filter(x -> a.minute().isBefore(x.minute())).findFirst().orElseThrow();
            paths.add(List.of(a, b));
            LocalDateTime lastIn = ins.get(ins.size() - 1).minute();
            outs.stream().filter(x -> x.minute().isBefore(lastIn)).forEach(x -> ids.add(x.id()));   // 뒤에 M→T 가 있는 S→M
            ins.stream().filter(x -> a.minute().isBefore(x.minute())).forEach(x -> ids.add(x.id()));  // 앞에 S→M 이 있는 M→T
        }
        paths.sort(Comparator.comparing((List<ScatterGatherLeg> p) -> p.get(0).ts())
                .thenComparingLong(p -> p.get(0).id()));
        return new ScatterGatherAlert(legs.get(0).target(), e, source, paths, ids.stream().sorted().toList());
    }

    /** 창 끝 = reference_ts = E+1분. 뜻은 (E-H, E] — 다른 룰과 같다. */
    public LocalDateTime windowEnd(LocalDateTime minute) {
        return minute.plusMinutes(1);
    }

    public LocalDateTime windowStart(LocalDateTime minute) {
        return windowEnd(minute).minusHours(windowHours);
    }
}
