package com.team.aml.cases.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /cases/{case_no}/note-histories 쿼리 (계약서 C8). 정렬은 최신 먼저로 고정, 형식 오류는 400 INVALID_REQUEST. */
public record NoteHistoryQuery(@Min(0) Integer page, @Min(1) @Max(100) Integer size) {

    static final int DEFAULT_SIZE = 20;

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
