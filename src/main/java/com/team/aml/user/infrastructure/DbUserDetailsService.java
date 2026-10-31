package com.team.aml.user.infrastructure;

import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.shared.security.LoginCandidate;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 때 users 를 login_id 로 읽는다 (SCRUM-19). shared 는 user 를 모르므로(ArchitectureTest) 여기 둔다.
 * 비활성·SYSTEM·해시 없음(canLogin() == false)은 없는 사용자와 똑같이 처리 — 응답에서 사유가 드러나지 않는다.
 */
@Component
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DbUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) {
        User u = users.findByLoginId(loginId)
                .filter(User::canLogin)
                .orElseThrow(() -> new UsernameNotFoundException("로그인할 수 없는 사용자"));   // 입력값은 메시지에 넣지 않는다
        return new LoginCandidate(new AppUserDetails(u.getId(), u.getLoginId(), u.getDisplayName(), u.getRole().name()),
                u.getPasswordHash());
    }
}
