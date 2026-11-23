package com.team.aml.cases.application;

import com.team.aml.cases.domain.CasePolicy;
import com.team.aml.cases.domain.CasePolicySource;
import com.team.aml.cases.domain.PolicyNotConfiguredException;
import com.team.aml.detection.application.CountAlertScores;
import com.team.aml.detection.domain.AlertScoreStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 경보 위험 등급 집계 (SCRUM-360) — DB 없이. 케이스와 같은 운영 정책으로 나누고, 정책이 없으면 등급만 뺀다. */
class GetAlertRiskStatsTest {

    private static final LocalDate DAY = LocalDate.of(2022, 9, 8);

    private final CasePolicySource policies = mock(CasePolicySource.class);
    private final CountAlertScores counts = mock(CountAlertScores.class);
    private final GetAlertRiskStats sut = new GetAlertRiskStats(policies, counts);

    @Test
    void 정상_운영_정책의_경계값으로_나누고_정책_버전을_함께_준다() {
        when(policies.current()).thenReturn(CasePolicy.of("v1", new BigDecimal("0.5"), new BigDecimal("0.7")));
        AlertScoreStats stats = new AlertScoreStats(1624, 1624, 35L, 17L, 1572L, 0, 0, 35);
        when(counts.count(DAY, new BigDecimal("0.5"), new BigDecimal("0.7"))).thenReturn(stats);

        GetAlertRiskStats.Result r = sut.execute(DAY);

        assertThat(r.high()).isEqualTo(35L);
        assertThat(r.medium()).isEqualTo(17L);
        assertThat(r.low()).isEqualTo(1572L);
        assertThat(r.caseOpened()).isEqualTo(35);
        assertThat(r.policyVersion()).isEqualTo("v1");
        assertThat(r.date()).isEqualTo(DAY);
    }

    @Test
    void 경계_정책이_없으면_오류가_아니라_등급_없이_건수만() {   // 평가 전이라 경계값이 비어 있을 수 있음(미결 23)
        when(policies.current()).thenThrow(new PolicyNotConfiguredException("정책 버전과 경계값이 모두 필요합니다"));
        AlertScoreStats stats = new AlertScoreStats(10, 4, null, null, null, 6, 0, 0);
        when(counts.count(null, null, null)).thenReturn(stats);

        GetAlertRiskStats.Result r = sut.execute(null);

        assertThat(r.high()).isNull();
        assertThat(r.total()).isEqualTo(10);
        assertThat(r.unscored()).isEqualTo(6);
        assertThat(r.policyVersion()).isNull();
        verify(counts).count(null, null, null);
    }
}
