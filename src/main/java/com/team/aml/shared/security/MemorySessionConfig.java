package com.team.aml.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * 세션을 서버 메모리(Tomcat)에 둔다. 재시작하면 모두 로그아웃(받아들임).
 * SecurityConfig 에서 옮겨 왔다 — 다음 단계에서 세션 저장소(Redis)를 설정으로 바꿀 수 있게 (SCRUM-273).
 */
@Configuration
public class MemorySessionConfig {

    /** 세션 만료·무효화를 SessionRegistry 에 알린다 — 없으면 maximumSessions 가 이미 끝난 세션도 센다. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 로그인 세션 목록. maximumSessions(1) 와 UserSessions(관리자가 남의 세션을 끊을 때)가 같은 것을 쓴다 (SCRUM-135 ②).
     * bean 이라 HttpSessionEventPublisher 의 만료·무효화 이벤트를 받는다.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }
}
