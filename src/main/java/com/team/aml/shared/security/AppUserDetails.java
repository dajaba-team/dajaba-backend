package com.team.aml.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.Objects;

/**
 * 세션에 들어가는 사용자. users 테이블의 id·login_id·role 만 든다. 비밀번호 해시는 세션에 넣지 않는다.
 * Spring Security 의 hasRole("ADMIN") 은 권한 문자열 "ROLE_ADMIN" 을 찾으므로 접두어를 붙인다.
 * 같은 사람인지는 userId 로만 본다 — SessionRegistry 가 주체를 키로 세션을 세므로, 역할·이름이 바뀌어도
 * 같은 사람이어야 maximumSessions(1) 가 이전 세션(이전 역할)을 끊는다 (SCRUM-19 검토).
 */
public record AppUserDetails(Long userId, String loginId, String displayName, String role) implements UserDetails {

    @Override public boolean equals(Object o) { return o instanceof AppUserDetails other && Objects.equals(userId, other.userId); }
    @Override public int hashCode() { return Objects.hashCode(userId); }

    @Override public List<GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_" + role)); }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return loginId; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
