package com.team.aml.cases.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 화면 계좌 가림 (미결 19). DB 없이 돈다. */
class AccountDisplayTest {

    @Test
    void 정상_은행ID는_두고_번호는_끝_4자리만() {
        assertThat(AccountDisplay.maskedNo("010_8000EBD30")).isEqualTo("010_****BD30");
    }

    @Test
    void 경계_번호가_4자리_이하면_끝자리도_가린다() {
        assertThat(AccountDisplay.maskedNo("010_BD30")).isEqualTo("010_****");
        assertThat(AccountDisplay.maskedNo("8000EBD30")).isEqualTo("****BD30");   // 구분자 없음
        assertThat(AccountDisplay.maskedNo(null)).isNull();
    }

    @Test
    void account_ref는_같은_계좌면_같고_다르면_다르며_원본이_보이지_않는다() {
        String ref = AccountDisplay.ref("010_8000EBD30");

        assertThat(ref).matches("ACR-[0-9A-F]{12}").doesNotContain("8000EBD30");
        assertThat(AccountDisplay.ref("010_8000EBD30")).isEqualTo(ref);
        assertThat(AccountDisplay.ref("011_8000EBD30")).isNotEqualTo(ref);   // 끝 4자리가 같아도 구별
    }
}
