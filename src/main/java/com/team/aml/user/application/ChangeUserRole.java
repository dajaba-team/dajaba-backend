package com.team.aml.user.application;

import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.security.UserSessions;
import com.team.aml.user.domain.AssigneeHasActiveCasesException;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할 변경 (SCRUM-135 ②). 역할 쌍(조사역 ↔ 승인자만) 검사는 User.changeRole 이 한다(400 details.role).
 * 여기서는 관리자 본인·SYSTEM(AdminUserTargets)과, 조사역 → 승인자일 때 진행 중 담당 건수(409 ASSIGNEE_HAS_ACTIVE_CASES)만 본다.
 * 성공하면 ROLE_CHANGED + 커밋 뒤 그 사람 세션 끊김.
 */
@Service
@Transactional
public class ChangeUserRole {

    private final AdminUserTargets targets;
    private final AccessLogWriter accessLog;
    private final UserSessions sessions;

    public ChangeUserRole(AdminUserTargets targets, AccessLogWriter accessLog, UserSessions sessions) {
        this.targets = targets;
        this.accessLog = accessLog;
        this.sessions = sessions;
    }

    public AdminUserView execute(long id, Role to) {
        User u = targets.requireChangeable(id);
        if (u.getRole() == Role.INVESTIGATOR && to == Role.APPROVER) {
            long active = targets.activeCaseCount(u);
            if (active > 0) {
                throw new AssigneeHasActiveCasesException(id, active);
            }
        }
        u.changeRole(to);
        accessLog.roleChanged(id);
        sessions.expireAll(id);
        return targets.view(u);
    }
}
