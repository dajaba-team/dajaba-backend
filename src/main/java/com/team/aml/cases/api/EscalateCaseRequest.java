package com.team.aml.cases.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** POST /cases/{case_no}/escalate (계약서 A4). 사유 1~2,000자 필수 — 공백만이면 400. */
public record EscalateCaseRequest(@NotBlank @Size(max = 2000) String comment,
                                  @NotNull @PositiveOrZero Long version) {
}
