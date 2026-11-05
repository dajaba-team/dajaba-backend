package com.team.aml.source.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetExpectationTest {

    private static final DatasetExpectation EXPECTED = DatasetExpectation.HI_SMALL;
    private static final PatternMatchStats ALL_MATCHED = new PatternMatchStats(3_209, 0, 0);

    @Test
    void 실측값과_같으면_불일치가_없다() {
        assertThat(EXPECTED.mismatches(stats(518_581, LocalDateTime.of(2022, 9, 18, 16, 18), 0), ALL_MATCHED))
                .isEmpty();
    }

    @Test
    void 다른_항목만_건수로_알려준다() {
        assertThat(EXPECTED.mismatches(stats(518_573, LocalDateTime.of(2022, 9, 18, 16, 19), 0), ALL_MATCHED))
                .containsExactly("마지막 거래 시각 2022-09-18T16:19 (기대 2022-09-18T16:18)",
                        "계좌 수 518573 (기대 518581)");
    }

    @Test
    void 계좌_파일에_없는_거래_계좌나_패턴_미매칭_다중매칭이_있으면_불일치다() {   // 준영 요청 적재 후 검증 (SCRUM-69)
        assertThat(EXPECTED.mismatches(stats(518_581, LocalDateTime.of(2022, 9, 18, 16, 18), 7),
                new PatternMatchStats(3_206, 2, 1)))
                .containsExactly("계좌 파일에 없는 거래 계좌 수 7 (기대 0)",
                        "패턴 미매칭 행 수 2 (기대 0)",
                        "패턴 다중매칭 행 수 1 (기대 0)");
    }

    private static DatasetStats stats(long accounts, LocalDateTime lastTs, long orphanAccounts) {
        return new DatasetStats(5_078_345, 5_177, 591_212, 3_209, 370,
                LocalDateTime.of(2022, 9, 1, 0, 0), lastTs, accounts, 166_207, 515_088, orphanAccounts);
    }
}
