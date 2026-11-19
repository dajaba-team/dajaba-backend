package com.team.aml.detection.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * R05 거래 한 다리 — 후보 (출발 S, 도착 T) 의 S→M(toTarget = false) 또는 M→T(toTarget = true).
 * from·to 는 거래의 실제 송신·수신 계좌 (저장 전 검사에서 다시 본다).
 */
public record ScatterGatherLeg(String source, String target, boolean toTarget, long id, LocalDateTime ts,
                               String from, String to) {

    /** 중간 계좌 M — S→M 이면 받은 쪽, M→T 면 보낸 쪽. */
    public String mid() {
        return toTarget ? from : to;
    }

    LocalDateTime minute() {
        return ts.truncatedTo(ChronoUnit.MINUTES);
    }
}
