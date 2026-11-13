package com.team.aml.detection.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** cases AccountDisplayTest 와 같은 입력·같은 기대값 — 세 복사본이 어긋나면 여기서 깨진다. */
class DetectionAccountDisplayTest {

    @Test
    void 정상_은행ID는_두고_번호는_끝_4자리만() {
        assertThat(DetectionAccountDisplay.maskedNo("010_8000EBD30")).isEqualTo("010_****BD30");
    }

    @Test
    void 경계_번호가_4자리_이하면_끝자리도_가린다() {
        assertThat(DetectionAccountDisplay.maskedNo("010_BD30")).isEqualTo("010_****");
        assertThat(DetectionAccountDisplay.maskedNo("8000EBD30")).isEqualTo("****BD30");
        assertThat(DetectionAccountDisplay.maskedNo(null)).isNull();
    }

    @Test
    void account_ref는_케이스_화면과_같은_값이다() {
        // SHA-256("010_8000EBD30") 앞 12자리 — cases AccountDisplay 와 같은 규칙이면 같은 값
        assertThat(DetectionAccountDisplay.ref("010_8000EBD30")).isEqualTo("ACR-68B10BCDF439");
        assertThat(DetectionAccountDisplay.ref("011_8000EBD30")).isNotEqualTo("ACR-68B10BCDF439");
        assertThat(DetectionAccountDisplay.ref(null)).isNull();
    }
}
