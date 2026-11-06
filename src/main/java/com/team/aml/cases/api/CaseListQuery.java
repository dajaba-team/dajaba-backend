package com.team.aml.cases.api;

import com.team.aml.cases.application.ListCases;
import com.team.aml.cases.domain.CaseStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

/**
 * GET /cases 쿼리 (계약서 C1, CASES 명세 API-01). 형식 검사는 여기서 끝낸다 — 틀리면 400 INVALID_REQUEST.
 * status 는 쉼표로 여럿({@code OPEN,REVIEWING}), assignee 는 {@code me} 또는 login_id, from·to 는 케이스 생성일(양 끝 포함).
 */
public record CaseListQuery(List<CaseStatus> status,
                            @Size(min = 1, max = 64) String assignee,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @Pattern(regexp = "(score|created_at|status),(asc|desc)") String sort,
                            @Min(0) Integer page,
                            @Min(1) @Max(100) Integer size) {

    static final int DEFAULT_SIZE = 20;

    @AssertTrue(message = "from 은 to 보다 늦을 수 없습니다")
    public boolean isPeriodInOrder() {
        return from == null || to == null || !from.isAfter(to);
    }

    ListCases.Query toQuery() {
        return new ListCases.Query(status == null || status.isEmpty() ? null : EnumSet.copyOf(status),
                assignee, from, to, sort, page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }
}
