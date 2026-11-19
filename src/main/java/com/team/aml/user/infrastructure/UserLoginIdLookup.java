package com.team.aml.user.infrastructure;

import com.team.aml.shared.security.LoginIdLookup;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** UserSessions 가 세션을 끊을 때 login_id 를 읽는다 (SCRUM-273). login_id 는 바꾸는 기능이 없어 세션 색인과 늘 같다. */
@Component
public class UserLoginIdLookup implements LoginIdLookup {

    private final UserRepository users;

    public UserLoginIdLookup(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<String> loginIdOf(long userId) {
        return users.findById(userId).map(User::getLoginId);
    }
}
