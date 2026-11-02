package com.team.aml.source.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetExpectationTest {

    private static final DatasetExpectation EXPECTED = DatasetExpectation.HI_SMALL;

    @Test
    void 실측값과_같으면_불일치가_없다() {
        assertThat(EXPECTED.mismatches(stats(518_581, LocalDateTime.of(2022, 9, 18, 16, 18), 7))).isEmpty();
    }

    @Test
    void 다른_항목만_건수로_알려준다() {
        assertThat(EXPECTED.mismatches(stats(518_573, LocalDateTime.of(2022, 9, 18, 16, 19), 0)))
                .containsExactly("마지막 거래 시각 2022-09-18T16:19 (기대 2022-09-18T16:18)",
                        "계좌 수 518573 (기대 518581)");
    }

    /** 미연결 계좌 수는 기록만 하는 값이라 불일치에 들어가지 않는다. */
    private static DatasetStats stats(long accounts, LocalDateTime lastTs, long orphanAccounts) {
        return new DatasetStats(5_078_345, 5_177, 591_212, 3_209, 370,
                LocalDateTime.of(2022, 9, 1, 0, 0), lastTs, accounts, 166_207, orphanAccounts);
    }
}
