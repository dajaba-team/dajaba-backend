package com.team.aml.str.domain;

import java.util.List;

/**
 * narrative 단락 이름. 두 세트가 있고 한 본문은 반드시 한 세트만 쓴다 (NarrativeSections).
 * enum 선언 순서가 곧 화면·해시의 단락 순서다 — 각 세트 안의 순서를 바꾸지 않는다.
 */
public enum StrSection {
    // V1 (narrative_v1, 6섹션, 규약 5.2) — 이미 만든 초안·승인본이 쓴다. 지우지 않는다
    SUMMARY,
    WHO,
    WHAT_WHEN_WHERE,
    HOW,
    WHY_SUSPICIOUS,
    CONCLUSION,

    // V2 (narrative_v2, 서식 별지 제1호 서술 7항목 — 서식 안건 3·6, 11/6~). 새 초안은 이것
    SUBJECT,      // 누가
    DATE,         // 언제
    PLACE,        // 어디서
    INSTRUMENT,   // 무엇으로(거래 수단)
    METHOD,       // 어떻게
    GROUNDS,      // 왜 의심되는지
    OPINION;      // 보고 기관 의견

    /** 6섹션 세트, 순서대로. */
    public static final List<StrSection> V1 = List.of(SUMMARY, WHO, WHAT_WHEN_WHERE, HOW, WHY_SUSPICIOUS, CONCLUSION);

    /** 7항목 세트, 순서대로. */
    public static final List<StrSection> V2 = List.of(SUBJECT, DATE, PLACE, INSTRUMENT, METHOD, GROUNDS, OPINION);

    /** 받는 세트 전부. 이름이 겹치지 않아 단락 이름만 보고 어느 세트인지 안다. */
    static final List<List<StrSection>> SETS = List.of(V1, V2);
}