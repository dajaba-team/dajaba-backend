package com.team.aml.str.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * POST /cases/{case_no}/close-with-draft 본문 (계약서 A6, 11/4 채영 — A5 /close 와 같은 본문이라 확인창 하나로 된다).
 * close_reason 을 enum 이 아니라 문자열 + @Pattern 으로 받는 이유: str api 는 cases 의 CloseReason 을 볼 수 없다(ArchUnit).
 * 길이·필수 규칙은 cases CloseCaseRequest(A5)와 같다. 초안 버전은 안 보낸다(계약서 A6).
 */
public record CloseCaseWithDraftRequest(
        @NotNull @Pattern(regexp = "FALSE_POSITIVE|DUPLICATE|NO_ACTION") String closeReason,
        @NotBlank @Size(max = 2000) String comment,
        @NotNull @PositiveOrZero Long version) {
}
