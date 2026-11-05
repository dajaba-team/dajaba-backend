package com.team.aml.str.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 본문 단락 구성 검사 — StrDraft.create 와 NarrativeHash 가 같은 규칙을 쓴다.
 * 규칙: 본문은 V1(6섹션) 또는 V2(7항목) 중 <b>한 세트</b>의 단락을 각각 정확히 한 번씩. 두 세트가 섞이면 거절 (11/6 서술부 7항목 전환).
 * 오류 메시지에는 개수와 단락 이름만 넣고 본문 글자는 넣지 않는다.
 */
final class NarrativeSections {

    private NarrativeSections() {}

    /** 맞으면 그 세트를 돌려준다. 아니면 IllegalArgumentException. what = 메시지 앞머리("AI 원문 단락", "본문 단락"). */
    static List<StrSection> requireOneSet(List<NarrativeSection> narrative, String what) {
        Map<StrSection, Integer> counts = new EnumMap<>(StrSection.class);
        for (NarrativeSection n : narrative) {
            counts.merge(n.section(), 1, Integer::sum);
        }
        List<StrSection> set = closestSet(counts);

        List<StrSection> missing = set.stream().filter(s -> !counts.containsKey(s)).toList();
        List<StrSection> duplicated = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        List<StrSection> foreign = counts.keySet().stream().filter(s -> !set.contains(s)).toList();

        if (narrative.size() != set.size() || !missing.isEmpty() || !duplicated.isEmpty() || !foreign.isEmpty()) {
            throw new IllegalArgumentException(what + "은 6섹션(V1) 또는 7항목(V2) 중 한 세트를 각각 한 번씩이어야 한다"
                    + " (받은 개수 " + narrative.size() + ", 기준 " + (set == StrSection.V1 ? "V1" : "V2")
                    + ", 빠짐 " + missing + ", 중복 " + duplicated + ", 다른 세트 " + foreign + ")");
        }
        return set;
    }

    /** 단락 이름이 가장 많이 겹치는 세트. 하나도 안 겹치면 새 세트(V2). */
    private static List<StrSection> closestSet(Map<StrSection, Integer> counts) {
        List<List<StrSection>> sets = new ArrayList<>(StrSection.SETS);
        sets.sort(Comparator.comparingLong((List<StrSection> s) -> s.stream().filter(counts::containsKey).count())
                .reversed()
                .thenComparing(s -> s == StrSection.V2 ? 0 : 1));
        return sets.get(0);
    }

    /** 단락 순서(enum 선언 순서) 그대로 줄 세운 새 목록. 한 세트 안에서는 그 세트의 순서와 같다. */
    static List<NarrativeSection> inSectionOrder(List<NarrativeSection> narrative) {
        return narrative.stream().sorted(Comparator.comparing(NarrativeSection::section)).toList();
    }
}