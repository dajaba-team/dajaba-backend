package com.team.aml.detection.domain;

import com.team.aml.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.Map;

/** 409 DATE_OUT_OF_RANGE — 적재된 거래 기간 밖 날짜. */
public class DateOutOfRangeException extends DomainException {
    public DateOutOfRangeException(LocalDate date, DataRange range) {
        super(date + " 은 데이터 기간(" + range.first() + " ~ " + range.last() + ") 밖입니다",
                Map.of("date", date.toString(), "first_date", range.first().toString(),
                        "last_date", range.last().toString()));
    }
}
