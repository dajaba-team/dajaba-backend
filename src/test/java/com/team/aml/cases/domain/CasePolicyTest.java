package com.team.aml.cases.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.team.aml.cases.domain.ScoreBand.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CasePolicy 등급 계산 단위 테스트.
 * 경계값 0.5·0.8 은 테스트용 값이다 — 실제 운영 경계는 협의 전이라 코드에 기본값을 두지 않는다 (명세 §7.1).
 */
class CasePolicyTest {

    private static final String VERSION = "test-policy-v1";

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static CasePolicy policy() {
        return CasePolicy.of(VERSION, d("0.5"), d("0.8"));
    }

    private static ScoreBand band(String score) {
        return policy().classify(d(score), null).band();
    }

    // ── HIGH 경계 (medium_to_high = 0.8) ─────────────────────

    @Test
    void HIGH_경계_바로_아래는_MEDIUM() {
        assertThat(band("0.7999999")).isEqualTo(MEDIUM);
    }

    @Test
    void HIGH_경계와_같으면_HIGH() {
        assertThat(band("0.8")).isEqualTo(HIGH);
        assertThat(band("0.800")).isEqualTo(HIGH);   // 자릿수가 달라도 같은 값
    }

    @Test
    void HIGH_경계_바로_위는_HIGH() {
        assertThat(band("0.8000001")).isEqualTo(HIGH);
    }

    // ── MEDIUM 경계 (low_to_medium = 0.5) ────────────────────

    @Test
    void MEDIUM_경계_바로_아래는_LOW() {
        assertThat(band("0.4999999")).isEqualTo(LOW);
    }

    @Test
    void MEDIUM_경계와_같으면_MEDIUM() {
        assertThat(band("0.5")).isEqualTo(MEDIUM);
    }

    @Test
    void MEDIUM_경계_바로_위는_MEDIUM() {
        assertThat(band("0.5000001")).isEqualTo(MEDIUM);
    }

    // ── 범위 끝 ─────────────────────────────────────────────

    @Test
    void 점수_0은_LOW_1은_HIGH() {
        assertThat(band("0")).isEqualTo(LOW);
        assertThat(band("1")).isEqualTo(HIGH);
    }

    // ── AI 추천값과 적용값 분리 ──────────────────────────────

    @Test
    void AI_추천_임계값은_등급을_바꾸지_않는다() {
        // 추천값 0.6 이면 0.7 이 HIGH 처럼 보이지만, 적용 경계는 0.8 이다
        ScoreClassification result = policy().classify(d("0.7"), d("0.6"));

        assertThat(result.band()).isEqualTo(MEDIUM);
        assertThat(result.recommendedThreshold()).isEqualByComparingTo("0.6");
        assertThat(result.mediumToHigh()).isEqualByComparingTo("0.8");
    }

    @Test
    void 분류_결과에_정책_버전과_적용_경계가_남는다() {
        ScoreClassification result = policy().classify(d("0.94"), d("0.85"));

        assertThat(result.band()).isEqualTo(HIGH);
        assertThat(result.policyVersion()).isEqualTo(VERSION);
        assertThat(result.score()).isEqualByComparingTo("0.94");
        assertThat(result.lowToMedium()).isEqualByComparingTo("0.5");
        assertThat(result.mediumToHigh()).isEqualByComparingTo("0.8");
    }

    // ── 점수 누락·잘못된 점수: LOW 로 바꾸지 않는다 ──────────

    @Test
    void 점수가_없으면_LOW가_아니라_거부한다() {
        assertThatThrownBy(() -> policy().classify(null, d("0.8")))
                .isInstanceOf(InvalidScoreException.class);
    }

    @Test
    void 점수가_0_미만이거나_1_초과면_거부한다() {
        assertThatThrownBy(() -> policy().classify(d("-0.0001"), null))
                .isInstanceOf(InvalidScoreException.class);
        assertThatThrownBy(() -> policy().classify(d("1.0001"), null))
                .isInstanceOf(InvalidScoreException.class);
    }

    // ── 정책 미설정·잘못된 경계 ──────────────────────────────

    @Test
    void 정책_버전이나_경계값이_없으면_POLICY_NOT_CONFIGURED() {
        assertThatThrownBy(() -> CasePolicy.of(null, d("0.5"), d("0.8")))
                .isInstanceOf(PolicyNotConfiguredException.class)
                .satisfies(e -> assertThat(((PolicyNotConfiguredException) e).code())
                        .isEqualTo("POLICY_NOT_CONFIGURED"));
        assertThatThrownBy(() -> CasePolicy.of(" ", d("0.5"), d("0.8")))
                .isInstanceOf(PolicyNotConfiguredException.class);
        assertThatThrownBy(() -> CasePolicy.of(VERSION, null, d("0.8")))
                .isInstanceOf(PolicyNotConfiguredException.class);
        assertThatThrownBy(() -> CasePolicy.of(VERSION, d("0.5"), null))
                .isInstanceOf(PolicyNotConfiguredException.class);
    }

    @Test
    void 경계는_0_이상_low_미만_high_1_이하여야_한다() {
        assertThatThrownBy(() -> CasePolicy.of(VERSION, d("0.8"), d("0.8")))   // low == high
                .isInstanceOf(PolicyNotConfiguredException.class);
        assertThatThrownBy(() -> CasePolicy.of(VERSION, d("0.9"), d("0.8")))   // low > high
                .isInstanceOf(PolicyNotConfiguredException.class);
        assertThatThrownBy(() -> CasePolicy.of(VERSION, d("-0.1"), d("0.8")))
                .isInstanceOf(PolicyNotConfiguredException.class);
        assertThatThrownBy(() -> CasePolicy.of(VERSION, d("0.5"), d("1.1")))
                .isInstanceOf(PolicyNotConfiguredException.class);
    }

    @Test
    void 경계_0과_1도_허용한다() {
        CasePolicy edge = CasePolicy.of(VERSION, d("0"), d("1"));

        assertThat(edge.classify(d("0"), null).band()).isEqualTo(MEDIUM);
        assertThat(edge.classify(d("0.9999"), null).band()).isEqualTo(MEDIUM);
        assertThat(edge.classify(d("1"), null).band()).isEqualTo(HIGH);
    }
}
