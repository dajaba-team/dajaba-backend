package com.team.aml.str.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * POST /approvals/{draft_no}/reject 본문 (계약서 P3, 안건 24). 코드·사유 둘 다 필수 — 없거나 틀리면 400 INVALID_REQUEST.
 * reason_code 를 enum 이 아니라 문자열 + @Pattern 으로 받는 이유: str api 는 cases 의 RejectionReasonCode 를 볼 수 없다(ArchUnit).
 * 사유 길이는 케이스 사유(EscalateCaseRequest)와 같은 @Size(max = 2000).
 */
public record RejectStrDraftRequest(
        @NotNull @PositiveOrZero Long caseVersion,
        @NotNull @Pattern(regexp = "NEED_REVISION|NOT_REPORTABLE") String reasonCode,
        @NotBlank @Size(max = 2000) String comment) {
}
