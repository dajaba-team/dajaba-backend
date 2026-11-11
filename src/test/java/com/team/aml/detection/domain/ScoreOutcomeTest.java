package com.team.aml.detection.domain;

import com.team.aml.shared.ai.AiDtos.ScoreResult;
import com.team.aml.shared.ai.AiDtos.ScoringMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 점수 응답 항목 → SCORED / FAILED(코드) (SD 명세 §6 저장 규칙). DB 없이. */
class ScoreOutcomeTest {

    private static final ScoringMeta META = new ScoringMeta("xgb-1", "f1", "srv", 12);
    private static final String AT = "2026-11-10T01:20:41Z";

    private static ScoreResult item(String status, String predictionId, Double score, String code,
                                    ScoringMeta meta, String producedAt) {
        return new ScoreResult("ALT-1", predictionId, score, 0.7, status, code, null, List.of(), List.of(),
                meta, producedAt, null);
    }

    private static ScoreResult ok(String predictionId, ScoringMeta meta, String producedAt) {
        return item("OK", predictionId, 0.42, null, meta, producedAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OK", "SHORT_HISTORY", "MISSING_INPUT"})
    void 점수가_있는_세_상태는_SCORED(String status) {   // 이력 부족·일부 결측도 실패가 아님
        ScoreOutcome o = ScoreOutcome.of(item(status, "PRD-1", 0.42, null, META, AT));

        assertThat(o.scored()).isTrue();
        assertThat(o.failureCode()).isNull();
        assertThat(o.producedAt()).isEqualTo(OffsetDateTime.parse(AT));
        assertThat(o.retryable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSACTION_NOT_FOUND", "HISTORY_NOT_SUPPORTED", "REQUIRED_INPUT_MISSING", "COMPUTE_ERROR"})
    void FAILED는_코드를_그대로_다시_보내는_건_COMPUTE_ERROR만(String code) {
        ScoreOutcome o = ScoreOutcome.of(item("FAILED", null, null, code, META, AT));

        assertThat(o.scored()).isFalse();
        assertThat(o.failureCode()).isEqualTo(code);
        assertThat(o.retryable()).isEqualTo("COMPUTE_ERROR".equals(code));
    }

    @Test
    void FAILED인데_코드가_없거나_모르는_값이면_코드_없음_다시_보낼_대상() {   // DB CHECK 4값 밖은 넣지 않는다
        assertThat(ScoreOutcome.of(item("FAILED", null, null, null, META, AT)).failureCode()).isNull();
        ScoreOutcome unknown = ScoreOutcome.of(item("FAILED", null, null, "NEW_CODE", META, AT));
        assertThat(unknown.failureCode()).isNull();
        assertThat(unknown.retryable()).isTrue();
    }

    @Test
    void 모르는_상태나_상태_없음은_FAILED_코드_없음() {
        assertThat(ScoreOutcome.of(item("UNKNOWN", "PRD-1", 0.4, null, META, AT)).scored()).isFalse();
        assertThat(ScoreOutcome.of(item(null, "PRD-1", 0.4, null, META, AT)).scored()).isFalse();
    }

    @Test
    void prediction_id가_없거나_비었거나_64자를_넘으면_FAILED_코드_없음() {
        for (String bad : new String[]{null, "", " ", "p".repeat(65)}) {
            ScoreOutcome o = ScoreOutcome.of(ok(bad, META, AT));
            assertThat(o.scored()).as("prediction_id=%s", bad).isFalse();
            assertThat(o.failureCode()).isNull();
        }
        assertThat(ScoreOutcome.of(ok("p".repeat(64), META, AT)).scored()).isTrue();
    }

    @Test
    void 버전은_빈_문자열도_되고_없거나_64자를_넘으면_FAILED() {   // SCRUM-204 — 빈 값은 계약상 허용
        assertThat(ScoreOutcome.of(ok("PRD-1", new ScoringMeta("", "", "", 0), AT)).scored()).isTrue();
        assertThat(ScoreOutcome.of(ok("PRD-1", new ScoringMeta(null, "f1", "srv", 0), AT)).scored()).isFalse();
        assertThat(ScoreOutcome.of(ok("PRD-1", new ScoringMeta("m".repeat(65), "f1", "srv", 0), AT)).scored()).isFalse();
        assertThat(ScoreOutcome.of(ok("PRD-1", null, AT)).scored()).isFalse();
    }

    @Test
    void produced_at은_오프셋이_있어야_한다() {
        assertThat(ScoreOutcome.of(ok("PRD-1", META, "2026-11-10T01:20:41.123456+00:00")).scored()).isTrue();
        assertThat(ScoreOutcome.of(ok("PRD-1", META, "2026-11-10T01:20:41")).scored()).isFalse();
        assertThat(ScoreOutcome.of(ok("PRD-1", META, "abc")).scored()).isFalse();
        assertThat(ScoreOutcome.of(ok("PRD-1", META, null)).scored()).isFalse();
    }

    @Test
    void 응답에_없는_경보는_FAILED_코드_없음() {
        assertThat(ScoreOutcome.missing()).isEqualTo(new ScoreOutcome(false, null, null));
        assertThat(ScoreOutcome.missing().retryable()).isTrue();
    }
}
