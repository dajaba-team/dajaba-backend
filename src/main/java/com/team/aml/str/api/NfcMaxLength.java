package com.team.aml.str.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.text.Normalizer;

/**
 * NFC 로 맞춘 뒤 글자 수(String.length()) 가 max 이하 — StrDraft 도메인·AI 규약·화면(text.normalize('NFC').length)과 같은 기준.
 * @Size 는 원문 길이를 세서 자소가 나뉜 한글(NFD)을 더 길게 센다. null 은 통과(@NotBlank 몫).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NfcMaxLength.Validator.class)
public @interface NfcMaxLength {

    int value();

    String message() default "NFC 기준 {value}자를 넘습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NfcMaxLength, String> {

        private int max;

        @Override
        public void initialize(NfcMaxLength annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(String text, ConstraintValidatorContext context) {
            return text == null || Normalizer.normalize(text, Normalizer.Form.NFC).length() <= max;
        }
    }
}
