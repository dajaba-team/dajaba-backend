package com.team.aml.cases.application;

import com.team.aml.cases.domain.InvalidAssigneeException;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserNotFoundException;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 배정·재배정 대상 확인 (API-03·04). 없는 login_id 404 USER_NOT_FOUND, 비활성·조사역 아님 409 INVALID_ASSIGNEE
 * (details.reason = NOT_ACTIVE_INVESTIGATOR). cases → user 는 읽기만 (02_structure §3).
 */
@Component
class ActiveInvestigators {

    private final UserRepository users;

    ActiveInvestigators(UserRepository users) {
        this.users = users;
    }

    /** @return 그 조사역의 users.id */
    long require(String caseNo, String loginId) {
        User u = users.findByLoginId(loginId).orElseThrow(() -> new UserNotFoundException(loginId));
        if (!u.isActive() || u.getRole() != Role.INVESTIGATOR) {
            throw new InvalidAssigneeException(caseNo, loginId, InvalidAssigneeException.Reason.NOT_ACTIVE_INVESTIGATOR);
        }
        return u.getId();
    }
}
