package com.team.aml.user.application;

import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 관리자 계정 목록 조회 (SCRUM-135 ①). 정렬은 리포지토리 파생 메서드에서 고정한다.
 * active_case_count 는 그 페이지의 조사역 행만 한 번에 센다 — 쿼리 1번, 조사역이 없으면 0번 (SCRUM-135 ②).
 */
@Service
@Transactional(readOnly = true)
public class ListUsers {

    private final UserRepository users;
    private final ActiveCaseCounter activeCases;

    public ListUsers(UserRepository users, ActiveCaseCounter activeCases) {
        this.users = users;
        this.activeCases = activeCases;
    }

    public Page<AdminUserView> execute(Role role, boolean active, int page, int size) {
        Page<User> found = users.findPage(role, active, PageRequest.of(page, size));
        List<Long> investigators = found.stream()
                .filter(u -> u.getRole() == Role.INVESTIGATOR).map(User::getId).toList();
        Map<Long, Long> counts = investigators.isEmpty() ? Map.of() : activeCases.countActiveByAssignees(investigators);
        return found.map(u -> AdminUserView.of(u, counts.getOrDefault(u.getId(), 0L)));
    }
}
