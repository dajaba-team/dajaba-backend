package com.team.aml.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Redis 세션 모드의 로그아웃 — 세션 무효화 뒤 Redis 의 세션 해시까지 지운다 (SCRUM-273).
 * Spring Session 은 무효화한 세션을 "만료 0초"로 바꿔 5분 동안 남겨 둔다(SecurityContext 그대로). 로그아웃이면 바로 지운다.
 * 색인(login_id)·만료 키 정리는 무효화(deleteById)가 하고, 여기선 남은 해시 하나만.
 * SecurityConfig 의 다른 로그아웃 처리(SecurityContextLogoutHandler)보다 먼저 돈다 — 세션이 이미 없으면 그쪽은 컨텍스트만 비운다.
 */
class RedisSessionLogoutHandler implements LogoutHandler {

    private final StringRedisTemplate redis;
    private final String sessionKeyPrefix;

    RedisSessionLogoutHandler(StringRedisTemplate redis, String namespace) {
        this.redis = redis;
        this.sessionKeyPrefix = namespace + ":sessions:";
    }

    @Override
    public void logout(HttpServletRequest req, HttpServletResponse res, Authentication auth) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return;
        }
        String id = session.getId();
        session.invalidate();
        redis.delete(sessionKeyPrefix + id);
    }
}
