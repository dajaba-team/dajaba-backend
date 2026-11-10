package com.team.aml.str.api;

import com.team.aml.str.domain.StrSection;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** S3 본문 문장 검사(400) — 스프링 없이. 글자 수는 NFC 기준(도메인·AI·화면과 같음). */
class ReviseStrDraftRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<ConstraintViolation<ReviseStrDraftRequest>> check(ReviseStrDraftRequest.SectionEdit... edits) {
        return VALIDATOR.validate(new ReviseStrDraftRequest(3L, List.of(edits)));
    }

    private static ReviseStrDraftRequest.SectionEdit edit(StrSection s, String text) {
        return new ReviseStrDraftRequest.SectionEdit(s, text);
    }

    // 1
    @Test
    void 정상_NFC_5000자는_통과하고_자소가_나뉜_한글도_완성형_글자_수로_센다() {
        String nfd = Normalizer.normalize("가".repeat(5_000), Normalizer.Form.NFD);   // 원문 길이 10,000
        assertThat(nfd.length()).isEqualTo(10_000);

        assertThat(check(edit(StrSection.METHOD, "가".repeat(5_000)))).isEmpty();
        assertThat(check(edit(StrSection.METHOD, nfd))).isEmpty();
    }

    // 2
    @Test
    void 실패_5001자_빈_문장_같은_단락_두_번은_위반() {
        assertThat(check(edit(StrSection.METHOD, "가".repeat(5_001)))).extracting(v -> v.getPropertyPath().toString())
                .containsExactly("sections[0].text");
        assertThat(check(edit(StrSection.METHOD, "  "))).isNotEmpty();
        assertThat(check(edit(StrSection.METHOD, "a"), edit(StrSection.METHOD, "b")))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("sectionsUnique");
        assertThat(VALIDATOR.validate(new ReviseStrDraftRequest(3L, List.of()))).isNotEmpty();
    }
}
