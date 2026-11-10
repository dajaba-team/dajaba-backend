package com.team.aml.user.application;

/**
 * 관리자 계정 목록 항목 (SCRUM-135 ①). activeCaseCount 는 지금 null.
 * SCRUM-135 ②에서 INVESTIGATOR 행만 채우고, 조사역 아닌 행은 계속 null.
 */
public record AdminUserView(Long id, String loginId, String displayName, String role,
                            boolean isActive, Long activeCaseCount) {}
