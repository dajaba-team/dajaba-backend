package com.team.aml.cases.api;

import com.team.aml.cases.application.RequestOpenCases;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 케이스 생성 배치 (CASES 명세 API-14, 계약서 B4). ADMIN 만 — SecurityConfig 의 /api/v1/admin/** 규칙. */
@Tag(name = "batches")
@RestController
@RequestMapping("/api/v1/admin/batches")
public class OpenCasesController {

    private final RequestOpenCases requestOpenCases;

    public OpenCasesController(RequestOpenCases requestOpenCases) {
        this.requestOpenCases = requestOpenCases;
    }

    @Operation(summary = "케이스 생성",
            description = "그 날짜의 점수 있는 경보 중 HIGH 만 케이스로 만든다. 202 로 접수하고 뒤에서 처리 — 결과는 배치 조회로 확인. "
                    + "정책 없음 409 POLICY_NOT_CONFIGURED, 실행 중 409 BATCH_RUNNING, 이미 성공 409 BATCH_ALREADY_DONE")
    @PostMapping("/open-cases")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RequestOpenCases.Accepted openCases(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return requestOpenCases.execute(date);
    }
}
