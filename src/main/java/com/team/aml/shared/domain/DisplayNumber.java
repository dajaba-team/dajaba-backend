package com.team.aml.shared.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 사람이 보는 번호 형식 한 곳 (레퍼런스 8.7). 번호 자체는 PostgreSQL 시퀀스(ops.alert_no_seq, ops.case_no_seq — V005·V006)에서 받는다.
 * MAX(id)+1 방식은 금지 — 두 트랜잭션이 같은 번호를 받을 수 있다.
 */
public final class DisplayNumber {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DisplayNumber() {}

    /** ALT-20220903-000142 */
    public static String alertNo(LocalDate batchDate, long seq) {
        return "ALT-" + batchDate.format(YYYYMMDD) + "-" + String.format("%06d", seq);
    }

    /** CASE-2022-000057 */
    public static String caseNo(int referenceYear, long seq) {
        return "CASE-" + referenceYear + "-" + String.format("%06d", seq);
    }
}
