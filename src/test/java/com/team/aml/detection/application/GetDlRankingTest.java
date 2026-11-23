package com.team.aml.detection.application;

import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.domain.DlRankingAlertRow;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.ai.AiDtos.DlRankedAlert;
import com.team.aml.shared.ai.AiDtos.DlRankingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 대조 순서·예외 범위·로그 (SCRUM-376) — DB 없이 가짜 저장소. 실제 쿼리는 GetDlRankingDbTest. */
@ExtendWith(OutputCaptureExtension.class)
class GetDlRankingTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final String MODEL = "xgb-v1";
    private static final String NO1 = "ALT-20220907-000001";
    private static final String NO2 = "ALT-20220907-000002";

    private final AiClient ai = mock(AiClient.class);

    @Test
    void 정상_Available_순서_있는_맵은_바꿀_수_없다() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO2, "R01", 0.9, 1), alert(NO1, "R02", 0.5, 2)));
        GetDlRanking use = new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL), row(NO2, "R01", "0.9", MODEL)));

        Available ok = (Available) use.execute(DATE);

        assertThat(ok.ranks().keySet()).containsExactly(NO2, NO1);
        assertThatThrownBy(() -> ok.ranks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 이유는_집합_룰_모델_점수_순으로_처음_하나() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1), alert(NO2, "R01", 0.9, 2)));

        // 모델·점수 둘 다 틀림 → 모델
        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.1", "old"), row(NO2, "R01", "0.9", MODEL)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.MODEL_MISMATCH));
        // 뒤 경보 룰 틀림 + 앞 경보 모델 틀림 → 룰(경보 순서와 상관없이 단계가 먼저)
        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", "old"), row(NO2, "R99", "0.9", MODEL)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.RULE_MISMATCH));
        // 집합 틀림 + 룰 틀림 → 집합
        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R99", "0.5", MODEL)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.ALERT_SET_MISMATCH));
    }

    @Test
    void 실패_DB_예외는_삼키지_않고_그대로_던진다() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1)));
        GetDlRanking use = new GetDlRanking(ai, d -> {
            throw new DataAccessResourceFailureException("db down");
        });

        assertThatThrownBy(() -> use.execute(DATE)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void AI가_실패하면_DB를_읽지_않는다() {
        when(ai.getDlRankings(DATE)).thenThrow(new AiClientException("AI_TIMEOUT", "x"));
        AtomicInteger reads = new AtomicInteger();

        assertThat(new GetDlRanking(ai, d -> {
            reads.incrementAndGet();
            return List.of();
        }).execute(DATE)).isEqualTo(new Unavailable(Reason.AI_UNAVAILABLE));
        assertThat(reads).hasValue(0);
    }

    @Test
    void 실패_날짜_null은_NPE() {
        assertThatThrownBy(() -> new GetDlRanking(ai, d -> List.of()).execute(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 로그에는_날짜_건수_이유_시간만_경보_번호_점수_없음(CapturedOutput out) {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.123456, 1)));
        new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.123456", MODEL))).execute(DATE);
        new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.654321", MODEL))).execute(DATE);
        when(ai.getDlRankings(DATE)).thenThrow(new AiClientException("DL_RANKING_UNAVAILABLE", "비밀 문장 " + NO1));
        new GetDlRanking(ai, d -> List.of()).execute(DATE);

        assertThat(out.getAll()).contains("2022-09-07").contains("SCORE_MISMATCH").contains("AI_UNAVAILABLE")
                .contains("DL_RANKING_UNAVAILABLE")
                .doesNotContain("ALT-").doesNotContain("0.123456").doesNotContain("0.654321")
                .doesNotContain("비밀 문장").doesNotContain(MODEL);
    }

    private static DlRankedAlert alert(String no, String rule, double ml, int rank) {
        return new DlRankedAlert(no, rule, ml, null, 0.5, 1, 1, null, rank, rank);
    }

    private static DlRankingResponse response(DlRankedAlert... alerts) {
        return new DlRankingResponse(DATE, "a".repeat(64), MODEL, "dl", 10, 8, alerts.length, 0, 0, List.of(alerts));
    }

    private static DlRankingAlertRow row(String no, String rule, String score, String model) {
        return new DlRankingAlertRow(no, rule, score == null ? null : new BigDecimal(score), model);
    }
}
