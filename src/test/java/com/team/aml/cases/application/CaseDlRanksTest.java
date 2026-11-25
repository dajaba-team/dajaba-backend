package com.team.aml.cases.application;

import com.team.aml.cases.application.CaseDlRank.Status;
import com.team.aml.cases.application.CaseDlRanks.AlertRef;
import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.DlRank;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.application.GetDlRanking;
import com.team.aml.shared.ai.AiDtos.DlFallbackReason;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 케이스 목록·상세 순위 붙이기 (SCRUM-420) — GetDlRanking 은 가짜. 날짜마다 한 번·Unavailable 은 그날 전부. */
class CaseDlRanksTest {

    private static final LocalDate D7 = LocalDate.of(2022, 9, 7);
    private static final LocalDate D1 = LocalDate.of(2022, 9, 1);
    private static final String A1 = "ALT-20220907-000001";
    private static final String A2 = "ALT-20220907-000002";
    private static final String A3 = "ALT-20220907-000003";
    private static final String B1 = "ALT-20220901-000001";

    private final GetDlRanking getDlRanking = mock(GetDlRanking.class);
    private final CaseDlRanks ranks = new CaseDlRanks(getDlRanking);

    @Test
    void Available_이면_경보마다_순위_ML_점수_없으면_순위_null() {
        Map<String, DlRank> byNo = new LinkedHashMap<>();
        byNo.put(A1, new DlRank(1, 2, null, 0.81));
        byNo.put(A2, new DlRank(2, 2, DlFallbackReason.INCOMPLETE_DL_EVIDENCE, null));
        byNo.put(A3, new DlRank(null, null, DlFallbackReason.ML_UNSCORED, null));
        when(getDlRanking.execute(D7)).thenReturn(new Available(D7, "0".repeat(64), "ml", "dl", byNo));

        Map<String, CaseDlRank> got = ranks.forAlerts(List.of(new AlertRef(A1, D7), new AlertRef(A2, D7),
                new AlertRef(A3, D7)));

        assertThat(got.get(A1)).isEqualTo(new CaseDlRank(Status.AVAILABLE, null, 1, 2, null, 0.81));
        assertThat(got.get(A2)).isEqualTo(
                new CaseDlRank(Status.AVAILABLE, null, 2, 2, DlFallbackReason.INCOMPLETE_DL_EVIDENCE, null));
        assertThat(got.get(A3)).isEqualTo(
                new CaseDlRank(Status.AVAILABLE, null, null, null, DlFallbackReason.ML_UNSCORED, null));
        verify(getDlRanking, times(1)).execute(D7);   // 같은 날짜는 한 번
    }

    @Test
    void Unavailable_이면_그날_경보_전부_조회_불가_새_이유도_그대로() {
        when(getDlRanking.execute(D7)).thenReturn(new Unavailable(Reason.EVIDENCE_MISMATCH));
        when(getDlRanking.execute(D1)).thenReturn(new Unavailable(Reason.REFERENCE_MISMATCH));

        Map<String, CaseDlRank> got = ranks.forAlerts(List.of(new AlertRef(A1, D7), new AlertRef(B1, D1),
                new AlertRef(A2, D7)));

        assertThat(got.get(A1)).isEqualTo(new CaseDlRank(Status.UNAVAILABLE, "EVIDENCE_MISMATCH", null, null, null, null));
        assertThat(got.get(A2).reason()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(got.get(B1).reason()).isEqualTo("REFERENCE_MISMATCH");
        verify(getDlRanking, times(1)).execute(D7);
        verify(getDlRanking, times(1)).execute(D1);
    }

    @Test
    void Available_인데_경보가_없으면_엉뚱한_순위_대신_조회_불가() {
        when(getDlRanking.execute(D7)).thenReturn(new Available(D7, "0".repeat(64), "ml", "dl", Map.of()));

        assertThat(ranks.forAlert(A1, D7))
                .isEqualTo(new CaseDlRank(Status.UNAVAILABLE, "ALERT_SET_MISMATCH", null, null, null, null));
    }

    @Test
    void 번호나_날짜가_없는_경보는_부르지_않고_응답은_조회_불가로_채운다() {
        Map<String, CaseDlRank> got = ranks.forAlerts(Arrays.asList(new AlertRef(null, D7), new AlertRef(A1, null), null));

        assertThat(got).isEmpty();
        verifyNoInteractions(getDlRanking);
        assertThat(CaseDlRank.orUnavailable(got.get(A1)).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void DB_예외는_숨기지_않는다() {
        when(getDlRanking.execute(D7)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> ranks.forAlert(A1, D7)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
