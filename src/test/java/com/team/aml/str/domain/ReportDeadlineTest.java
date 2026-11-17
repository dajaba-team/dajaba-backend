package com.team.aml.str.domain;

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
}
