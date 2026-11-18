package com.team.aml.str.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** POST /approvals/{draft_no}/report 본문 (계약서 P4) — 화면이 읽은 case_version 만. 없으면 400 INVALID_REQUEST. */
public record ReportStrDraftRequest(@NotNull @PositiveOrZero Long caseVersion) {
}
