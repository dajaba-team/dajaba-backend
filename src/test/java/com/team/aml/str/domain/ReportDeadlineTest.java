package com.team.aml.str.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마감 계산 (서식 회의 10/30 결정 1): 승인일 다음 영업일부터 3번째 영업일 23:59 KST, 토·일만 제외.
 * 달력: 2026-11-16 월 ~ 11-22 일.
 */
class ReportDeadlineTest {

    @ParameterizedTest(name = "{0} 승인 → {1}")
    @CsvSource({
            // 승인 (KST)                   , 마감 (KST)
            "2026-11-16T10:00+09:00[Asia/Seoul], 2026-11-19T23:59:59+09:00[Asia/Seoul]",   // 월 → 목
            "2026-11-18T15:00+09:00[Asia/Seoul], 2026-11-23T23:59:59+09:00[Asia/Seoul]",   // 수 → 다음 월 (토·일 건너뜀)
            "2026-11-20T09:00+09:00[Asia/Seoul], 2026-11-25T23:59:59+09:00[Asia/Seoul]",   // 금 → 다음 수
            "2026-11-21T12:00+09:00[Asia/Seoul], 2026-11-25T23:59:59+09:00[Asia/Seoul]",   // 토 승인 → 월·화·수
            "2026-11-22T23:30+09:00[Asia/Seoul], 2026-11-25T23:59:59+09:00[Asia/Seoul]",   // 일 밤 승인도 같음
    })
    void 승인일은_세지_않고_토일을_빼고_3번째_영업일_23시59분(ZonedDateTime approved, ZonedDateTime due) {
        assertThat(ReportDeadline.dueAt(approved.toInstant())).isEqualTo(due.toInstant());
    }

    @ParameterizedTest(name = "UTC {0} = 서울 날짜로 센다")
    @CsvSource({
            "2026-11-16T14:59:00Z, 2026-11-19T14:59:59Z",   // 서울 11-16(월) 23:59 → 목 마감
            "2026-11-16T15:00:00Z, 2026-11-20T14:59:59Z",   // 서울 11-17(화) 00:00 → 금 마감 — UTC 날짜로 세면 하루 틀린다
    })
    void 날짜는_UTC가_아니라_서울_기준(Instant approvedAt, Instant due) {
        assertThat(ReportDeadline.dueAt(approvedAt)).isEqualTo(due);
    }

    // 마감 2026-11-20(금) 23:59:59 KST = 14:59:59Z. 23:59 분 전체가 제때, 다음 날 00:00 KST 부터 늦음 (11/17 결정·리뷰)
    @ParameterizedTest(name = "제출 {0} → 늦음 {1}")
    @CsvSource({
            "2026-11-20T14:58:59Z,        false",   // 23:58:59
            "2026-11-20T14:59:00Z,        false",   // 23:59:00
            "2026-11-20T14:59:59Z,        false",   // 23:59:59 (저장된 마감 값 그대로)
            "2026-11-20T14:59:59.500Z,    false",   // 23:59:59.5
            "2026-11-20T14:59:59.999999Z, false",   // 23:59:59.999999 — DB 최대 정밀도
            "2026-11-20T15:00:00Z,        true",    // 다음 날 00:00:00 KST
            "2026-11-20T15:00:00.000001Z, true",
            "2026-11-23T01:00:00Z,        true",    // 주말 지나 월요일
    })
    void 마감일_다음날_00시부터_늦음(Instant reportedAt, boolean late) {
        Instant due = ReportDeadline.dueAt(Instant.parse("2026-11-17T05:00:00Z"));   // 11-17(화) 승인

        assertThat(due).isEqualTo(Instant.parse("2026-11-20T14:59:59Z"));
        assertThat(ReportDeadline.isLate(due, reportedAt)).isEqualTo(late);
    }

    @Test
    void 늦음_판단은_저장된_마감값의_서울_날짜로_한다() {
        Instant due = Instant.parse("2026-11-20T14:59:59Z");   // KST 11-20 23:59:59

        assertThat(ReportDeadline.isLate(due, Instant.parse("2026-11-20T14:59:59.999999Z"))).isFalse();
        assertThat(ReportDeadline.isLate(due, Instant.parse("2026-11-20T15:00:00Z"))).isTrue();
    }
}
