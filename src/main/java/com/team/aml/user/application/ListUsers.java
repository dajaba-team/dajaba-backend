package com.team.aml.user.application;

import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 계정 목록 조회 (SCRUM-135 ①). 정렬은 리포지토리 파생 메서드에서 고정한다. */
@Service
@Transactional(readOnly = true)
public class ListUsers {

    private final UserRepository users;

    public ListUsers(UserRepository users) {
        this.users = users;
    }

    public Page<AdminUserView> execute(Role role, boolean active, int page, int size) {
        return users.findPage(role, active, PageRequest.of(page, size))
                .map(u -> new AdminUserView(u.getId(), u.getLoginId(), u.getDisplayName(),
                        u.getRole().name(), u.isActive(), null));
    }
}
