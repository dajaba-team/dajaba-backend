package com.team.aml.user.application;

import com.team.aml.shared.audit.AccessLogWriter;
import com.team.aml.user.domain.LoginIdTakenException;
import com.team.aml.user.domain.Role;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

/**
 * 계정 발급 (SCRUM-135 ②). 입력 형식은 요청 DTO 가 끝냈다. 비밀번호는 BCrypt 해시만 저장하고 어디에도 찍지 않는다.
 * 같은 아이디를 동시에 두 번 보내 exists 검사를 둘 다 지나도 DB unique 위반을 같은 409 LOGIN_ID_TAKEN 으로 바꾼다.
 */
@Service
@Transactional
public class CreateUser {

    /** V003 login_id unique 의 PostgreSQL 기본 이름. */
    static final String LOGIN_ID_UNIQUE = "users_login_id_key";
    private static final String UNIQUE_VIOLATION = "23505";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccessLogWriter accessLog;
    private final AdminUserTargets targets;

    public CreateUser(UserRepository users, PasswordEncoder encoder, AccessLogWriter accessLog, AdminUserTargets targets) {
        this.users = users;
        this.encoder = encoder;
        this.accessLog = accessLog;
        this.targets = targets;
    }

    public AdminUserView execute(String loginId, String displayName, Role role, String rawPassword) {
        if (users.existsByLoginId(loginId)) {
            throw new LoginIdTakenException(loginId);
        }
        User saved;
        try {
            saved = users.save(User.create(loginId, displayName, role, encoder.encode(rawPassword)));   // IDENTITY 라 바로 INSERT
        } catch (DataIntegrityViolationException e) {
            if (isLoginIdUnique(e)) {
                throw new LoginIdTakenException(loginId);
            }
            throw e;
        }
        accessLog.userCreated(saved.getId());
        return targets.view(saved);
    }

    static boolean isLoginIdUnique(DataIntegrityViolationException e) {
        return e.getMostSpecificCause() instanceof SQLException sql
                && UNIQUE_VIOLATION.equals(sql.getSQLState())
                && String.valueOf(sql.getMessage()).contains(LOGIN_ID_UNIQUE);
    }
}
