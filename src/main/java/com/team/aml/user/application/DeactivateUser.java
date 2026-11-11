package com.team.aml.user.application;

import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.shared.security.UserSessions;
import com.team.aml.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비활성화 (SCRUM-135 ②). 담당이 남아도 막지 않는다 — 응답 active_case_count 로 화면이 안내(11/7 D5).
 * USER_DEACTIVATED + 커밋 뒤 세션 끊김. 이미 비활성이면 기록·끊기 없이 지금 한 줄.
 */
@Service
@Transactional
public class DeactivateUser {

    private final AdminUserTargets targets;
    private final AccessLogWriter accessLog;
    private final UserSessions sessions;

    public DeactivateUser(AdminUserTargets targets, AccessLogWriter accessLog, UserSessions sessions) {
        this.targets = targets;
        this.accessLog = accessLog;
        this.sessions = sessions;
    }

    public AdminUserView execute(long id) {
        User u = targets.requireChangeable(id);
        if (u.isActive()) {
            u.deactivate();
            accessLog.userDeactivated(id);
            sessions.expireAll(id);
        }
        return targets.view(u);
    }
}
