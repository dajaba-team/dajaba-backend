package com.team.aml.user.application;

import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET /api/v1/auth/me — 프론트가 새로고침 뒤 메뉴를 그릴 때 (레퍼런스 9.1). */
@Service
@Transactional(readOnly = true)
public class GetMe {

    public record Result(Long id, String loginId, String displayName, String role) {}

    private final CurrentUser currentUser;
    private final UserRepository users;

    public GetMe(CurrentUser currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    public Result execute() {
        AppUserDetails me = currentUser.require();
        User u = users.findById(me.userId()).orElseThrow(() -> new IllegalStateException("세션 사용자가 DB 에 없습니다"));
        return new Result(u.getId(), u.getLoginId(), u.getDisplayName(), u.getRole().name());
    }
}
