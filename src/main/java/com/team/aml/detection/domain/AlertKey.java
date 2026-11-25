package com.team.aml.detection.domain;

import java.time.LocalDate;

/**
 * 경보 번호·배치 날짜 (SCRUM-420). 케이스 목록이 ML+DL 조사 순위({@code GetDlRanking})를 붙일 때
 * 케이스의 alert_id 로 경보 번호와 그 날짜를 찾는 데 쓴다.
 */
public record AlertKey(long alertId, String alertNo, LocalDate batchDate) {
}
