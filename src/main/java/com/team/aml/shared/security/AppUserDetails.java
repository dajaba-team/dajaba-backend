package com.team.aml.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

/**
 * 세션에 들어가는 사용자. users 테이블의 id·login_id·role 만 든다. 비밀번호 해시는 세션에 넣지 않는다.
 * Spring Security 의 hasRole("ADMIN") 은 권한 문자열 "ROLE_ADMIN" 을 찾으므로 접두어를 붙인다.
 */
public record AppUserDetails(Long userId, String loginId, String displayName, String role) implements UserDetails {

    @Override public List<GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_" + role)); }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return loginId; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
