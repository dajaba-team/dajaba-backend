package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.config.annotation.web.HttpSecurityBuilder;
import org.springframework.security.config.annotation.web.configurers.AbstractAuthenticationFilterConfigurer;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * JsonLoginFilter 를 formLogin 과 같은 경로로 등록한다. 부모 configurer 가 공유 객체에서
 * 세션 전략(ChangeSessionId + ConcurrentSessionControl + Register + CSRF 토큰 교체)과 SecurityContextRepository(세션)를 꺼내 넣어 준다.
 */
class JsonLoginConfigurer<H extends HttpSecurityBuilder<H>>
        extends AbstractAuthenticationFilterConfigurer<H, JsonLoginConfigurer<H>, JsonLoginFilter> {

    static final String LOGIN_URL = "/api/v1/auth/login";

    JsonLoginConfigurer(ObjectMapper om) {
        super(new JsonLoginFilter(om), LOGIN_URL);
    }

    @Override
    protected RequestMatcher createLoginProcessingUrlMatcher(String loginProcessingUrl) {
        return new AntPathRequestMatcher(loginProcessingUrl, "POST");
    }
}
