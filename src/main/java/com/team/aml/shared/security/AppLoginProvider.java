package com.team.aml.shared.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 비교는 Spring 의 DaoAuthenticationProvider 그대로 — 없는 ID 도 더미 해시와 비교해 응답 시간으로 계정 존재가 드러나지 않고,
 * UsernameNotFoundException 은 BadCredentials 로 바뀌어 사유가 숨는다. 바꾸는 건 하나: 성공하면 세션 주체를 해시 없는 AppUserDetails 로.
 */
class AppLoginProvider extends DaoAuthenticationProvider {

    AppLoginProvider(UserDetailsService users, PasswordEncoder encoder) {
        setUserDetailsService(users);
        setPasswordEncoder(encoder);
    }

    @Override
    protected Authentication createSuccessAuthentication(Object principal, Authentication authentication, UserDetails user) {
        AppUserDetails me = ((LoginCandidate) user).user();
        UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken.authenticated(me, null, me.getAuthorities());
        result.setDetails(authentication.getDetails());
        return result;
    }
}
