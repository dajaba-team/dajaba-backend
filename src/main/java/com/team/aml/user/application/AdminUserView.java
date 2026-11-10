package com.team.aml.user.application;

import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;

/**
 * 관리자 계정 목록 항목이자 계정 관리 쓰기 API(SCRUM-135 ②)의 응답 한 줄.
 * activeCaseCount 는 INVESTIGATOR 행만 진행 중 담당 건수(0 포함), 다른 역할은 null.
 */
public record AdminUserView(Long id, String loginId, String displayName, String role,
                            boolean isActive, Long activeCaseCount) {

    static AdminUserView of(User u, Long activeCaseCount) {
        return new AdminUserView(u.getId(), u.getLoginId(), u.getDisplayName(), u.getRole().name(), u.isActive(),
                u.getRole() == Role.INVESTIGATOR ? activeCaseCount : null);
    }
}
