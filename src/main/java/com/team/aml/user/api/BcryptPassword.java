package com.team.aml.user.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt 는 앞 72바이트만 쓴다 — Spring Security 6.3.4 는 넘는 부분을 예외 없이 버려서, 앞 72바이트만 같으면
 * 다른 비밀번호로도 로그인된다(로컬 확인). 규칙 8~128자(@Size)는 그대로 두고 UTF-8 72바이트를 넘으면 400 (SCRUM-135 ②, 04 §7).
 * 공백만인 값도 400 — 로그인(JsonLoginFilter)이 String.isBlank() 면 BCrypt 비교 전에 거절해서, 발급해도 로그인할 수 없다.
 * 같은 isBlank 를 쓴다(@NotBlank 는 trim 기준이라 전각 공백 U+3000 을 못 거른다). 값은 strip 하지 않는다 — 앞뒤 공백도 비밀번호.
 * null 은 통과 — @NotNull 이 따로 본다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptPassword.Validator.class)
@interface BcryptPassword {

    int MAX_BYTES = 72;

    String message() default "72바이트(영문 72자, 한글 24자)까지 쓸 수 있습니다";
    String BLANK_MESSAGE = "공백만으로는 쓸 수 없습니다";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.isBlank()) {   // 로그인과 같은 기준 — 메시지만 바꿔 같은 칸(details.<필드>)으로 낸다
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(BLANK_MESSAGE).addConstraintViolation();
                return false;
            }
            return value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}
