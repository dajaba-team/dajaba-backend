package com.team.aml.cases.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * POST /cases/{case_no}/assign (계약서 A1). 조사역은 {@code {version}}, ADMIN 은 {@code {assignee, version}}.
 * assignee 는 배정할 조사역 login_id — 작업자가 아니라 업무 입력이다. 작업자는 세션(CurrentUser).
 */
public record AssignCaseRequest(@Size(min = 1, max = 64) String assignee,
                                @NotNull @PositiveOrZero Long version) {
}
