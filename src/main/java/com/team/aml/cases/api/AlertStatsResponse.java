package com.team.aml.cases.api;

import com.team.aml.cases.application.GetAlertRiskStats;

import java.time.LocalDate;

/** 경보 통계 응답 (SCRUM-360). bands 는 운영 등급 경계값이 있을 때만. */
public record AlertStatsResponse(LocalDate date, long total, long scored, Bands bands, long unscored,
                                 long failed, long caseOpened, String policyVersion) {

    public record Bands(long high, long medium, long low) {}

    static AlertStatsResponse from(GetAlertRiskStats.Result r) {
        Bands bands = r.high() == null ? null : new Bands(r.high(), r.medium(), r.low());
        return new AlertStatsResponse(r.date(), r.total(), r.scored(), bands, r.unscored(), r.failed(),
                r.caseOpened(), r.policyVersion());
    }
}
