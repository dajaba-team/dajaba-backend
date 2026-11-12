package com.team.aml.str.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** S6 요청 본문 검사(400) — 스프링 없이. 의심 유형 1개 이상·중복 없음·16자, 지점 정도 1~5 필수 (계약서 S6 토글). */
class SubmitStrDraftRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> violations(List<String> types, Integer level) {
        return VALIDATOR.validate(new SubmitStrDraftRequest(3L, "v-1", types, level)).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    // 1
    @Test
    void 정상_유형_여러_개와_정도_1부터_5까지는_통과() {
        assertThat(violations(List.of("VI-1", "VI-4"), 1)).isEmpty();
        assertThat(violations(List.of("VI-1"), 5)).isEmpty();
        assertThat(violations(List.of("x".repeat(16)), 3)).isEmpty();
    }

    // 2
    @Test
    void 실패_유형이_없거나_중복이거나_17자면_위반() {
        assertThat(violations(List.of(), 3)).containsExactly("suspicionTypes");
        assertThat(violations(List.of("VI-1", "VI-1"), 3)).containsExactly("suspicionTypesUnique");
        assertThat(violations(List.of("x".repeat(17)), 3)).singleElement().asString()
                .startsWith("suspicionTypes[0]");                // 400 details 키 suspicion_types[0]
    }

    // 3
    @Test
    void 실패_정도가_없거나_0이거나_6이면_위반() {   // 기본값 없음 — 비워 두면 400
        assertThat(violations(List.of("VI-1"), null)).containsExactly("suspicionLevelBranch");
        assertThat(violations(List.of("VI-1"), 0)).containsExactly("suspicionLevelBranch");
        assertThat(violations(List.of("VI-1"), 6)).containsExactly("suspicionLevelBranch");
    }

    // 4
    @Test
    void 실패_case_version과_verification_id는_필수() {
        assertThat(VALIDATOR.validate(new SubmitStrDraftRequest(null, " ", List.of("VI-1"), 3)))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("caseVersion", "verificationId");
    }
}
