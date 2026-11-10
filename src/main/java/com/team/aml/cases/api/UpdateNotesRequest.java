package com.team.aml.cases.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** PUT /cases/{case_no}/notes (계약서 A3). 메모 0~10,000자 — null·누락·초과는 400, 빈 문자열은 비우기. */
public record UpdateNotesRequest(@NotNull @Size(max = 10000) String investigatorNotes,
                                 @NotNull @PositiveOrZero Long version) {
}
