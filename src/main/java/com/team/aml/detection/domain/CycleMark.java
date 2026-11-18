package com.team.aml.detection.domain;

import java.time.LocalDateTime;

/**
 * 1분 전 창 판단용 고리 요약 — 대상 계좌·닫힌 분별로 가장 늦게 시작한 고리의 첫 거래 시각.
 * "닫힌 분 < E 이고 첫 거래 ≥ E-H 인 고리가 있나" 는 이 값 하나로 판단된다 (가장 늦은 첫 거래가 조건을 못 맞추면 다른 고리도 못 맞춘다).
 * 과거 고리는 경보에 담지 않으니(지금 창에 남은 과거 고리가 있으면 1분 전 창에도 있어 경보가 안 난다) 거래 조합 전체를 가져오지 않는다.
 */
public record CycleMark(String accountKey, LocalDateTime closeMinute, LocalDateTime latestFirstTs) {
}
