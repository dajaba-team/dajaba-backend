package com.team.aml.detection.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 거래 단위 고리 하나 — 거래 id·시각·송신·수신 계좌를 시간순으로. 대상 계좌 = 첫 거래의 송신 계좌. */
public record CyclePath(List<Long> ids, List<LocalDateTime> ts, List<String> from, List<String> to) {

    /** 경로끼리는 첫 거래 시각순, 같으면 거래 id 순 (rules.md 2.1). */
    static final Comparator<CyclePath> ORDER = Comparator.comparing(CyclePath::firstTs)
            .thenComparing(CyclePath::ids, (x, y) -> {
                for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                    int c = Long.compare(x.get(i), y.get(i));
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(x.size(), y.size());
            });

    public CyclePath {
        ids = List.copyOf(ids);
        ts = List.copyOf(ts);
        from = List.copyOf(from);
        to = List.copyOf(to);
        if (ids.isEmpty() || ts.size() != ids.size() || from.size() != ids.size() || to.size() != ids.size()) {
            throw new IllegalArgumentException("고리의 거래 id·시각·계좌 수가 맞지 않는다");
        }
    }

    public String accountKey() {
        return from.get(0);
    }

    public LocalDateTime firstTs() {
        return ts.get(0);
    }

    /** E — 돌아오는 거래(마지막 거래)의 분. */
    public LocalDateTime closeMinute() {
        return ts.get(ts.size() - 1).truncatedTo(ChronoUnit.MINUTES);
    }

    public int hops() {
        return ids.size();
    }

    /** 1분 전 창 판단용 요약. */
    public CycleMark mark() {
        return new CycleMark(accountKey(), closeMinute(), firstTs());
    }

    /** rules.md 2.1 저장 전 검사 (R04): A 에서 시작해 A 로, 이어짐, 분 단위 증가, 창 안, 중간 계좌 겹침 없음, max_hops 이하. */
    public boolean validFor(CycleRule rule, LocalDateTime windowStart, LocalDateTime windowEnd) {
        int k = ids.size();
        if (k < 2 || k > rule.maxHops() || !from.get(0).equals(to.get(k - 1))) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        seen.add(from.get(0));
        for (int i = 0; i < k; i++) {
            if (ts.get(i).isBefore(windowStart) || !ts.get(i).isBefore(windowEnd)) {
                return false;
            }
            if (i > 0 && (!to.get(i - 1).equals(from.get(i))
                    || !ts.get(i - 1).truncatedTo(ChronoUnit.MINUTES).isBefore(ts.get(i).truncatedTo(ChronoUnit.MINUTES)))) {
                return false;
            }
            if (i < k - 1 && !seen.add(to.get(i))) {   // 중간 계좌는 서로 다르고 A 가 아님
                return false;
            }
        }
        return true;
    }
}
