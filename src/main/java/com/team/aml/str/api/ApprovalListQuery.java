package com.team.aml.str.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /approvals 쿼리 (계약서 P1). 정렬은 요청 오래된 순으로 고정, page 0부터·size 기본 20·최대 100 (계약서 2.3). */
public record ApprovalListQuery(@Min(0) Integer page, @Min(1) @Max(100) Integer size) {

    static final int DEFAULT_SIZE = 20;

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
