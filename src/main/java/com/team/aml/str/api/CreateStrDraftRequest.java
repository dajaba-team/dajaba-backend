package com.team.aml.str.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** POST /cases/{case_no}/str-drafts 본문 (계약서 S1). 화면이 읽은 케이스 version. */
public record CreateStrDraftRequest(@NotNull @PositiveOrZero Long caseVersion) {
}
