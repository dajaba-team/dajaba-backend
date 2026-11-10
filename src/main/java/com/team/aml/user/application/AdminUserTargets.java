package com.team.aml.user.application;

import com.team.aml.shared.security.CurrentUser;
import com.team.aml.user.domain.AccountChangeNotAllowedException;
import com.team.aml.user.domain.AccountChangeNotAllowedException.Reason;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserNotFoundException;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 계정 관리 쓰기 유스케이스가 같이 쓰는 대상 찾기·응답 한 줄 (SCRUM-135 ②).
 * 검사 순서: 없는 id 404 USER_NOT_FOUND → SYSTEM·관리자 본인 409 ACCOUNT_CHANGE_NOT_ALLOWED. 다른 관리자는 막지 않는다.
 */
@Component
public class AdminUserTargets {

    private final UserRepository users;
    private final CurrentUser currentUser;
    private final ActiveCaseCounter activeCases;

    public AdminUserTargets(UserRepository users, CurrentUser currentUser, ActiveCaseCounter activeCases) {
        this.users = users;
        this.currentUser = currentUser;
        this.activeCases = activeCases;
    }

    User requireChangeable(long id) {
        User u = users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        if (u.getId() == User.SYSTEM_ID || u.getRole() == Role.SYSTEM) {
            throw new AccountChangeNotAllowedException(id, Reason.SYSTEM);
        }
        if (u.getId().equals(currentUser.require().userId())) {   // 둘 다 Long — == 는 참조 비교
            throw new AccountChangeNotAllowedException(id, Reason.SELF);
        }
        return u;
    }

    AdminUserView view(User u) {
        return AdminUserView.of(u, u.getRole() == Role.INVESTIGATOR ? activeCases.countActive(u.getId()) : null);
    }
}
