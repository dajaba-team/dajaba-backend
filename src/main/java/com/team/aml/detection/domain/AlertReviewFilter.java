package com.team.aml.detection.domain;

import java.time.LocalDate;

/**
 * 관리자 경보 점검 목록 필터 (계약서 5.7 R1, SD 명세 §7). 전부 선택·AND, null 이면 안 거른다.
 * api 가 형식을 검사한 값만 온다 — scoreStatus 는 PENDING·SCORED·FAILED, scoreFailureCode 는 V005 CHECK 목록.
 */
public record AlertReviewFilter(LocalDate date, String ruleId, String scoreStatus, String scoreFailureCode,
                                Boolean caseOpened) {
}
