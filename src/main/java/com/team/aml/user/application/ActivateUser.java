package com.team.aml.user.application;

import com.team.aml.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재활성화 (SCRUM-135 ②). 기록 없음(access_logs action 값이 없다), 세션 그대로. 이미 활성이면 지금 한 줄. */
@Service
@Transactional
public class ActivateUser {

    private final AdminUserTargets targets;

    public ActivateUser(AdminUserTargets targets) {
        this.targets = targets;
    }

    public AdminUserView execute(long id) {
        User u = targets.requireChangeable(id);
        if (!u.isActive()) {
            u.activate();
        }
        return targets.view(u);
    }
}
