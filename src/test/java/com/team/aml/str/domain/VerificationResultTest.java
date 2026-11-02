package com.team.aml.str.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 검증 결과 단위 테스트 (스프링 없이 돈다)  AI 규약 status 4개(안건 9) */
public class VerificationResultTest {

    private static final String HASH = "a".repeat(64);   // 모양만 맞는 해시

    private static VerificationResult withStatus(VerificationStatus status) {
        return VerificationResult.of("v-1", "gen-1", 1, HASH, status);
    }

    // 1
    @Test
    void PASSED만_통과로_본다() {
        assertThat(withStatus(VerificationStatus.PASSED).isPassed()).isTrue();

        assertThat(withStatus(VerificationStatus.CODE_FAILED).isPassed()).isFalse();
        assertThat(withStatus(VerificationStatus.LLM_FAILED).isPassed()).isFalse();
        assertThat(withStatus(VerificationStatus.VERIFY_UNAVAILABLE).isPassed()).isFalse(); // "검증 못 함"도 승인 불가
    }

    // 2
    @Test
    void 잘못된_값으로는_검증_결과를_만들_수_없다() {
        VerificationStatus ok = VerificationStatus.PASSED;

        // 수정본 번호: 1 = AI 원문이 가장 작다
        assertThatThrownBy(() -> VerificationResult.of("v-1", "gen-1", 0, HASH, ok))
                .isInstanceOf(IllegalArgumentException.class);

        // 해시 모양: 소문자 16진수 64자만
        assertThatThrownBy(() -> VerificationResult.of("v-1", "gen-1", 1, HASH.toUpperCase(), ok))
                .isInstanceOf(IllegalArgumentException.class);              // 대문자
        assertThatThrownBy(() -> VerificationResult.of("v-1", "gen-1", 1, HASH.substring(1), ok))
                .isInstanceOf(IllegalArgumentException.class);              // 63자
        assertThatThrownBy(() -> VerificationResult.of("v-1", "gen-1", 1, null, ok))
                .isInstanceOf(IllegalArgumentException.class);

        // 번호 두 개는 비면 안 된다
        assertThatThrownBy(() -> VerificationResult.of(" ", "gen-1", 1, HASH, ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerificationResult.of("v-1", null, 1, HASH, ok))
                .isInstanceOf(IllegalArgumentException.class);

        // 상태 없음
        assertThatThrownBy(() -> VerificationResult.of("v-1", "gen-1", 1, HASH, null))
                .isInstanceOf(NullPointerException.class);

        // 경계: 1번 수정본(AI 원문)은 된다
        assertThat(VerificationResult.of("v-1", "gen-1", 1, HASH, ok).getRevisionNo()).isEqualTo(1);
    }
}
