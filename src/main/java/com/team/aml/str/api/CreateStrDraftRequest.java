package com.team.aml.str.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * POST /cases/{case_no}/str-drafts 본문 (계약서 S1). 화면이 읽은 케이스 version.
 * JSON 키는 case_version — 전역 SNAKE_CASE 라 @JsonProperty 를 붙이지 않는다.
 */
public record CreateStrDraftRequest(@NotNull @PositiveOrZero Long caseVersion) {
}

