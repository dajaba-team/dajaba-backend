package com.team.aml.user.application;

import com.team.aml.shared.security.UserSessions;
import com.team.aml.user.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 비밀번호 초기화 (SCRUM-135 ②). 길이는 요청 DTO 가 끝냈다. BCrypt 해시로 바꾸고 커밋 뒤 세션 끊김. 기록 없음. */
@Service
@Transactional
public class ResetUserPassword {

    private final AdminUserTargets targets;
    private final PasswordEncoder encoder;
    private final UserSessions sessions;

    public ResetUserPassword(AdminUserTargets targets, PasswordEncoder encoder, UserSessions sessions) {
        this.targets = targets;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    public AdminUserView execute(long id, String rawPassword) {
        String hash = encoder.encode(rawPassword);   // BCrypt(약 0.3초)는 행을 잠그기 전에 — 잠금을 짧게
        User u = targets.requireChangeable(id);
        u.changePasswordHash(hash);
        sessions.expireAll(id);
        return targets.view(u);
    }
}
