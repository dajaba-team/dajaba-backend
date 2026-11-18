package com.team.aml.str.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** A6 본문 검사(400) — 스프링 없이. 사유 세 값만·설명 1~2,000자·version 필수 (cases CloseCaseRequest 와 같은 규칙). */
class CloseCaseWithDraftRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violations(String reason, String comment, Long version) {
        return VALIDATOR.validate(new CloseCaseWithDraftRequest(reason, comment, version)).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    // 1
    @Test
    void 정상_세_사유와_2000자_설명은_통과() {
        for (String reason : new String[] {"FALSE_POSITIVE", "DUPLICATE", "NO_ACTION"}) {
            assertThat(violations(reason, "가".repeat(2_000), 3L)).as(reason).isEmpty();
        }
    }

    // 2
    @Test
    void 실패_사유가_없거나_다른_값이면_위반() {
        assertThat(violations(null, "닫기", 3L)).containsExactly("closeReason");
        assertThat(violations("NOT_REPORTABLE", "닫기", 3L)).containsExactly("closeReason");   // 반려 코드와 헷갈리지 않게
        assertThat(violations("no_action", "닫기", 3L)).containsExactly("closeReason");
    }

    // 3
    @Test
    void 실패_설명이_없거나_공백이거나_2001자이거나_version이_없으면_위반() {
        assertThat(violations("NO_ACTION", null, 3L)).containsExactly("comment");
        assertThat(violations("NO_ACTION", "  ", 3L)).containsExactly("comment");
        assertThat(violations("NO_ACTION", "가".repeat(2_001), 3L)).containsExactly("comment");
        assertThat(violations("NO_ACTION", "닫기", null)).containsExactly("version");
    }
}
