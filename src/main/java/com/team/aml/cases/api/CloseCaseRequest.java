package com.team.aml.cases.api;

import com.team.aml.cases.domain.CloseReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * POST /cases/{case_no}/close (계약서 A5, CASES 명세 API-06). 사유·설명 둘 다 필수.
 * 초안 있는 종료(A6 close-with-draft)도 같은 본문이지만 str 컨트롤러 몫이다.
 */
public record CloseCaseRequest(@NotNull CloseReason closeReason,
                               @NotBlank @Size(max = 2000) String comment,
                               @NotNull @PositiveOrZero Long version) {
}
