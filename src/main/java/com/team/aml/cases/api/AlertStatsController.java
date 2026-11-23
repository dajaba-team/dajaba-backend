package com.team.aml.cases.api;

import com.team.aml.cases.application.GetAlertRiskStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 관리자 대시보드 경보 통계 (SCRUM-360). ADMIN 만(/api/v1/admin/**). */
@Tag(name = "alerts")
@RestController
public class AlertStatsController {

    private final GetAlertRiskStats stats;

    public AlertStatsController(GetAlertRiskStats stats) {
        this.stats = stats;
    }

    @Operation(summary = "경보 위험 등급 집계",
            description = "기준일(없으면 전체)의 경보 수를 운영 등급 경계값으로 HIGH·MEDIUM·LOW 로 나눠 준다. "
                    + "점수 대기·실패·케이스 전환 수 포함. 경계값이 없으면 bands·policy_version 은 null. 건수만, 계좌·금액 없음")
    @GetMapping("/api/v1/admin/alerts/stats")
    public AlertStatsResponse stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return AlertStatsResponse.from(stats.execute(date));
    }
}
