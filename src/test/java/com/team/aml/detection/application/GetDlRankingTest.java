package com.team.aml.detection.application;

import com.team.aml.detection.application.DlRankingResult.Available;
import com.team.aml.detection.application.DlRankingResult.Reason;
import com.team.aml.detection.application.DlRankingResult.Unavailable;
import com.team.aml.detection.domain.DlRankingAlertRow;
import com.team.aml.detection.domain.EvidenceSource;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 대조 순서·예외 범위·로그 (SCRUM-376) — DB 없이 가짜 저장소. 실제 쿼리는 GetDlRankingDbTest.
 * 기준 시각·근거 두 단계(SCRUM-415): 헬퍼는 기본으로 그날 00:00:00 · 근거 `f.csv:1` 한 건이 양쪽에 같게 들어간다.
 */
@ExtendWith(OutputCaptureExtension.class)
class GetDlRankingTest {

    private static final LocalDate DATE = LocalDate.of(2022, 9, 7);
    private static final String MODEL = "xgb-v1";
    private static final String NO1 = "ALT-20220907-000001";
    private static final String NO2 = "ALT-20220907-000002";
    private static final LocalDateTime REF = DATE.atStartOfDay();
    private static final List<EvidenceSource> EVIDENCE = List.of(new EvidenceSource("f.csv", 1L));

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

    // ---- SCRUM-415 기준 시각·근거 ----

    @Test
    void 기준_시각만_1초_다르면_REFERENCE_MISMATCH() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T00:29:01", digest("f.csv:1"))));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, DATE.atTime(0, 29), EVIDENCE)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.REFERENCE_MISMATCH));
    }

    @Test
    void 기준_시각_초가_0이어도_초까지_쓴_T_모양으로_비교해_맞는다() {
        // LocalDateTime.toString() 은 2022-09-07T00:29 — 그걸로 비교하면 9/7 경보가 전부 어긋남
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T00:29:00", digest("f.csv:1"))));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, DATE.atTime(0, 29), EVIDENCE)))
                .execute(DATE)).isInstanceOf(Available.class);
    }

    @Test
    void 경계_DB_기준_시각의_초_아래는_버리고_비교한다() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T10:24:59", digest("f.csv:1"))));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL,
                DATE.atTime(10, 24, 59, 999_999_000), EVIDENCE))).execute(DATE)).isInstanceOf(Available.class);
    }

    @Test
    void 근거_한_행이_다르면_EVIDENCE_MISMATCH() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T00:00:00",
                digest("f.csv:1\nf.csv:3"))));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, REF,
                List.of(new EvidenceSource("f.csv", 1L), new EvidenceSource("f.csv", 2L)))))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.EVIDENCE_MISMATCH));
    }

    @Test
    void 근거는_DB가_준_순서와_상관없이_파일명_행번호_정수_순으로_계산해_맞는다() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T00:00:00",
                digest("a.csv:318130\na.csv:1639618\nb.csv:2"))));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, REF, List.of(
                new EvidenceSource("b.csv", 2L), new EvidenceSource("a.csv", 1639618L),
                new EvidenceSource("a.csv", 318130L))))).execute(DATE)).isInstanceOf(Available.class);
    }

    @Test
    void 경계_DB_경보에_근거가_없으면_EVIDENCE_MISMATCH() {
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1)));

        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, REF, List.of()))).execute(DATE))
                .isEqualTo(new Unavailable(Reason.EVIDENCE_MISMATCH));
    }

    @Test
    void 이유는_점수_다음_기준_시각_그다음_근거() {
        // 뒤 경보 점수 틀림 + 앞 경보 기준 시각·근거 틀림 → 점수
        when(ai.getDlRankings(DATE)).thenReturn(response(
                alert(NO1, "R02", 0.5, 1, "2022-09-07T00:00:01", digest("f.csv:9")),
                alert(NO2, "R01", 0.9, 2)));
        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL), row(NO2, "R01", "0.1", MODEL)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.SCORE_MISMATCH));
        // 뒤 경보 기준 시각 틀림 + 앞 경보 근거 틀림 → 기준 시각(경보 순서와 상관없이 단계가 먼저)
        when(ai.getDlRankings(DATE)).thenReturn(response(
                alert(NO1, "R02", 0.5, 1, "2022-09-07T00:00:00", digest("f.csv:9")),
                alert(NO2, "R01", 0.9, 2, "2022-09-07T00:00:01", digest("f.csv:1"))));
        assertThat(new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL), row(NO2, "R01", "0.9", MODEL)))
                .execute(DATE)).isEqualTo(new Unavailable(Reason.REFERENCE_MISMATCH));
    }

    @Test
    void 기존_다섯_이유는_이름_순서_그대로_뒤에_두_단계만_붙는다() {
        assertThat(Reason.values()).containsExactly(Reason.AI_UNAVAILABLE, Reason.ALERT_SET_MISMATCH,
                Reason.RULE_MISMATCH, Reason.MODEL_MISMATCH, Reason.SCORE_MISMATCH, Reason.REFERENCE_MISMATCH,
                Reason.EVIDENCE_MISMATCH);
    }

    @Test
    void 로그에는_새_이유만_나오고_기준_시각_digest_파일명은_없다(CapturedOutput out) {
        String secretDigest = digest("secret.csv:1");
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T11:11:11", secretDigest)));
        new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL))).execute(DATE);
        when(ai.getDlRankings(DATE)).thenReturn(response(alert(NO1, "R02", 0.5, 1, "2022-09-07T00:00:00", secretDigest)));
        new GetDlRanking(ai, d -> List.of(row(NO1, "R02", "0.5", MODEL, REF,
                List.of(new EvidenceSource("secret.csv", 2L))))).execute(DATE);

        assertThat(out.getAll()).contains("REFERENCE_MISMATCH").contains("EVIDENCE_MISMATCH")
                .doesNotContain("11:11:11").doesNotContain(secretDigest).doesNotContain("secret.csv")
                .doesNotContain("ALT-");
    }

    private static DlRankedAlert alert(String no, String rule, double ml, int rank) {
        return alert(no, rule, ml, rank, "2022-09-07T00:00:00", digest("f.csv:1"));
    }

    private static DlRankedAlert alert(String no, String rule, double ml, int rank, String referenceTs, String evidenceDigest) {
        return new DlRankedAlert(no, rule, ml, null, 0.5, 1, 1, null, rank, rank, referenceTs, evidenceDigest);
    }

    private static DlRankingResponse response(DlRankedAlert... alerts) {
        return new DlRankingResponse(DATE, "a".repeat(64), MODEL, "dl", 10, 8, alerts.length, 0, 0, List.of(alerts));
    }

    private static DlRankingAlertRow row(String no, String rule, String score, String model) {
        return row(no, rule, score, model, REF, EVIDENCE);
    }

    private static DlRankingAlertRow row(String no, String rule, String score, String model, LocalDateTime reference,
                                         List<EvidenceSource> evidence) {
        return new DlRankingAlertRow(no, rule, score == null ? null : new BigDecimal(score), model, reference, evidence);
    }

    /** 기대 digest 는 EvidenceDigest 를 쓰지 않고 따로 — 이미 정렬·`\n` 으로 이은 줄. */
    private static String digest(String joinedLines) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joinedLines.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
