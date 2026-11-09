package com.team.aml.cases.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** POST /cases/{case_no}/refresh-summary (계약서 A7). 화면이 읽은 cases.version 만 보낸다. */
public record RefreshSummaryRequest(@NotNull @PositiveOrZero Long version) {
}
