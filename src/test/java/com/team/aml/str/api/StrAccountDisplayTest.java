package com.team.aml.str.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** STR 응답의 계좌 가림 — cases/api/AccountDisplay 와 같은 결과여야 한다. DB 없이 돈다. */
class StrAccountDisplayTest {

    @Test
    void 정상_은행ID는_두고_번호는_끝_4자리만() {
        assertThat(StrAccountDisplay.maskedNo("010_8000EBD30")).isEqualTo("010_****BD30");
    }

    @Test
    void 경계_번호가_4자리_이하면_끝자리도_가린다() {
        assertThat(StrAccountDisplay.maskedNo("010_BD30")).isEqualTo("010_****");
        assertThat(StrAccountDisplay.maskedNo("010_D30")).isEqualTo("010_****");
    }

    @Test
    void 경계_null이면_null() {
        assertThat(StrAccountDisplay.maskedNo(null)).isNull();
        assertThat(StrAccountDisplay.ref(null)).isNull();
    }

    @Test
    void account_ref는_ACR로_시작하는_16자이고_같은_키면_늘_같다() {
        String ref = StrAccountDisplay.ref("010_8000EBD30");

        assertThat(ref).startsWith("ACR-").hasSize(16).matches("ACR-[0-9A-F]{12}").doesNotContain("8000EBD30");
        assertThat(StrAccountDisplay.ref("010_8000EBD30")).isEqualTo(ref);
    }
}
