package com.team.aml.source.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 적재가 맞았는지 판단하는 기대값 (규약 §1.5, 미결 14). 하나라도 다르면 적재를 실패시킨다. */
public record DatasetExpectation(long transactions, long launderingTransactions, long selfTransfers,
                                 long patternTransactions, long patternEvents,
                                 LocalDateTime firstTs, LocalDateTime lastTs,
                                 long accounts, long entities) {

    /** 11/1 실제 파일 전체 적재로 확인한 HI-Small 값. 계좌 수는 규약 518,573 대신 실측 518,581 (SCRUM-71). */
    public static final DatasetExpectation HI_SMALL = new DatasetExpectation(
            5_078_345, 5_177, 591_212, 3_209, 370,
            LocalDateTime.of(2022, 9, 1, 0, 0), LocalDateTime.of(2022, 9, 18, 16, 18),
            518_581, 166_207);

    /** 기대값과 다른 항목. 건수만 적어 error_message에 그대로 남길 수 있다. */
    public List<String> mismatches(DatasetStats actual) {
        List<String> result = new ArrayList<>();
        check(result, "거래 수", transactions, actual.transactions());
        check(result, "세탁 거래 수", launderingTransactions, actual.launderingTransactions());
        check(result, "자기계좌 거래 수", selfTransfers, actual.selfTransfers());
        check(result, "패턴 거래 수", patternTransactions, actual.patternTransactions());
        check(result, "패턴 사건 수", patternEvents, actual.patternEvents());
        check(result, "첫 거래 시각", firstTs, actual.firstTs());
        check(result, "마지막 거래 시각", lastTs, actual.lastTs());
        check(result, "계좌 수", accounts, actual.accounts());
        check(result, "법인 수", entities, actual.entities());
        return result;
    }

    private static void check(List<String> result, String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            result.add(name + " " + actual + " (기대 " + expected + ")");
        }
    }
}
