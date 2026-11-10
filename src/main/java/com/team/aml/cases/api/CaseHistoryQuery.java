package com.team.aml.cases.api;

import com.team.aml.cases.domain.CaseHistoryAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /cases/{case_no}/histories 쿼리 (계약서 C6). action 은 하나만, NOTE 는 C8 이라 400. size ≤ 100. */
public record CaseHistoryQuery(CaseHistoryAction action, @Min(0) Integer page, @Min(1) @Max(100) Integer size) {

    /** 검증 전용 — 쿼리 파라미터가 아니라 swagger 에서 숨긴다 (CaseListQuery 와 같음). */
    @Schema(hidden = true)
    @AssertTrue(message = "메모 이력은 note-histories 로 조회합니다")
    public boolean isNotNote() {
        return action != CaseHistoryAction.NOTE;
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
