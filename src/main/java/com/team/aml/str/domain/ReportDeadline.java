package com.team.aml.str.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 모의 보고 마감 (서식 회의 10/30 결정 1). 승인한 날은 세지 않고 다음 영업일부터 세어 3번째 영업일 23:59 KST.
 * 영업일 = 토·일을 뺀 날. 공휴일은 빼지 않는다 — 화면에 "공휴일 미반영" 표시(MVP).
 * 계산은 서울 시각, 저장·응답은 UTC(Instant). 승인 때 한 번 계산해 str_reports.report_due_at 에 고정한다
 * — 나중에 공휴일 규칙이 생겨도 지난 보고서의 마감은 바뀌지 않는다.
 * 23:59 는 그 분 끝(23:59:59)까지 인정한다(11/17 결정) — 화면에 23:59 로 보이는 제출이 늦음이 되지 않게.
 */
public final class ReportDeadline {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    static final int BUSINESS_DAYS = 3;
    static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    private ReportDeadline() {}

    public static Instant dueAt(Instant approvedAt) {
        Objects.requireNonNull(approvedAt, "approvedAt");
        LocalDate day = approvedAt.atZone(BUSINESS_ZONE).toLocalDate();
        int counted = 0;
        while (counted < BUSINESS_DAYS) {
            day = day.plusDays(1);
            if (isBusinessDay(day)) {
                counted++;
            }
        }
        return day.atTime(DUE_TIME).atZone(BUSINESS_ZONE).toInstant();
    }

    static boolean isBusinessDay(LocalDate day) {
        DayOfWeek d = day.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }
}
