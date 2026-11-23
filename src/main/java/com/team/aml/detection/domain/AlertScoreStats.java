package com.team.aml.detection.domain;

/**
 * 경보 점수 집계 (관리자 대시보드, SCRUM-360). 건수만 — 계좌·금액 없음.
 * high·medium·low 는 경계값을 받았을 때만(점수 있는 경보를 나눈 수), 없으면 null.
 */
public record AlertScoreStats(long total, long scored, Long high, Long medium, Long low,
                              long unscored, long failed, long caseOpened) {
}
