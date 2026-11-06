package com.team.aml.shared.api;

import com.team.aml.shared.batch.BatchRunQuery;
import com.team.aml.shared.batch.BatchRunView;
import com.team.aml.shared.batch.BatchStatus;
import com.team.aml.shared.batch.BatchType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 배치 상태 조회 (SD 명세 API 2). ADMIN 만 — SecurityConfig 의 /api/v1/admin/** 규칙. */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class BatchRunController {

    private final BatchRunQuery query;

    public BatchRunController(BatchRunQuery query) {
        this.query = query;
    }

    @Operation(summary = "배치 실행 한 건", description = "없으면 404 BATCH_RUN_NOT_FOUND")
    @GetMapping("/{batchRunId}")
    public BatchRunView get(@PathVariable long batchRunId) {
        return query.get(batchRunId);
    }

    @Operation(summary = "배치 실행 목록", description = "정렬은 started_at·batch_date, 기본 started_at,desc. size 최대 100")
    @GetMapping
    public PageResponse<BatchRunView> list(
            @RequestParam(required = false) BatchType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) BatchStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "started_at,desc")
            @Pattern(regexp = "(started_at|batch_date),(asc|desc)") String sort) {
        return PageResponse.from(query.search(type, date, status, page, size, sort));
    }
}
