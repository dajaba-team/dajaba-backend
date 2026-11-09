package com.team.aml.detection.domain;

import java.time.LocalDate;

/** 적재된 거래의 첫날·마지막 날. 스캔할 수 있는 날짜 범위 (하드코딩하지 않는다). */
public record DataRange(LocalDate first, LocalDate last) {
    public boolean contains(LocalDate date) {
        return !date.isBefore(first) && !date.isAfter(last);
    }
}
