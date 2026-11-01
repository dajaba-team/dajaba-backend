package com.team.aml.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * 로그인 검사 때만 쓰는 사용자 — 비밀번호 해시를 든다. 검사가 끝나면 AppLoginProvider 가 해시 없는 AppUserDetails 로 바꿔
 * 세션에 넣는다(해시는 세션에 들어가지 않는다). users 를 읽어 이걸 만드는 건 user/infrastructure/DbUserDetailsService.
 */
public record LoginCandidate(AppUserDetails user, String passwordHash) implements UserDetails {

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return user.getAuthorities(); }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return user.loginId(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }       // 비활성은 DbUserDetailsService 가 "없는 사용자"로 처리

    @Override public String toString() { return "LoginCandidate[" + user.loginId() + "]"; }   // 해시를 로그에 남기지 않는다
}
