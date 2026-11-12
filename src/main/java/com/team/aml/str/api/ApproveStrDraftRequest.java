package com.team.aml.str.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * POST /approvals/{draft_no}/approve 본문 (계약서 P2). 본점용 의심 정도 1~5 필수·기본값 없음 (계약서 5.5, 11/4 채영).
 * 없거나 범위 밖이면 400 INVALID_REQUEST — 도메인(StrDraft.recordHqSuspicion)도 같은 규칙으로 한 번 더 막는다.
 */
public record ApproveStrDraftRequest(
        @NotNull @PositiveOrZero Long caseVersion,
        @NotNull @Min(1) @Max(5) Integer suspicionLevelHq) {
}
