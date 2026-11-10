package com.team.aml.cases.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** POST /cases/{case_no}/reassign (계약서 A2). assignee 는 새 담당 조사역 login_id(필수), comment 선택 최대 2,000자. */
public record ReassignCaseRequest(@NotBlank @Size(max = 64) String assignee,
                                  @Size(max = 2000) String comment,
                                  @NotNull @PositiveOrZero Long version) {
}
