package com.team.aml.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 유스케이스가 "지금 누가 요청했나"를 꺼내는 유일한 통로 (레퍼런스 9.4).
 * 컨트롤러가 사용자 id 를 body 로 받는 일은 없다 — 프론트가 보내는 id 는 믿지 않는다.
 * 배치 스레드처럼 로그인이 없으면 Optional.empty(). 감사 컬럼은 SessionAuditorAware 가 1L(SYSTEM) 로 채운다.
 */
@Component
public class CurrentUser {

    public static final long SYSTEM_ID = 1L;

    public Optional<AppUserDetails> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails u)) {
            return Optional.empty();
        }
        return Optional.of(u);
    }

    /** 로그인한 사용자 id. 없으면 SYSTEM(1). 배치에서 쓴다. */
    public long idOrSystem() {
        return get().map(AppUserDetails::userId).orElse(SYSTEM_ID);
    }

    /** 로그인이 꼭 있어야 하는 유스케이스용. SecurityConfig 가 /api/** 를 막으므로 여기 오면 항상 있다. */
    public AppUserDetails require() {
        return get().orElseThrow(() -> new IllegalStateException("로그인 정보가 없습니다"));
    }
}
