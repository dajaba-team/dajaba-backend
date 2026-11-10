package com.team.aml.cases.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /cases/{case_no}/graph 쿼리 (계약서 C5). limit 기본 200, 1~1,000. 틀리면 400. */
public record CaseGraphQuery(@Min(1) @Max(1000) Integer limit) {

    int limitOrDefault() {
        return limit == null ? 200 : limit;
    }
}
