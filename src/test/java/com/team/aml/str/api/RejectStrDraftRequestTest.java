package com.team.aml.str.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** P3 반려 본문 검사(400) — 스프링 없이. 코드 두 값만, 사유 1~2,000자 둘 다 필수 (안건 24). */
class RejectStrDraftRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violations(String code, String comment) {
        return VALIDATOR.validate(new RejectStrDraftRequest(3L, code, comment)).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    // 1
    @Test
    void 정상_두_코드와_2000자_사유는_통과() {
        assertThat(violations("NEED_REVISION", "보완")).isEmpty();
        assertThat(violations("NOT_REPORTABLE", "가".repeat(2_000))).isEmpty();
    }

    // 2
    @Test
    void 실패_코드가_없거나_다른_값이면_위반() {
        assertThat(violations(null, "보완")).containsExactly("reasonCode");
        assertThat(violations("APPROVED", "보완")).containsExactly("reasonCode");
        assertThat(violations("need_revision", "보완")).containsExactly("reasonCode");   // 대소문자도 정확히
    }

    // 3
    @Test
    void 실패_사유가_없거나_공백이거나_2001자면_위반() {
        assertThat(violations("NEED_REVISION", null)).containsExactly("comment");
        assertThat(violations("NEED_REVISION", "   ")).containsExactly("comment");
        assertThat(violations("NEED_REVISION", "가".repeat(2_001))).containsExactly("comment");
    }
}
