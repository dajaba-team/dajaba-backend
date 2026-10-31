package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.config.AmlProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 세션 쿠키 + CSRF + URL 역할 규칙 (레퍼런스 9.3). 두 사람은 이 파일을 고칠 일이 거의 없다.
 *
 * 로그인: POST /api/v1/auth/login {login_id, password} (JsonLoginFilter, SCRUM-19).
 *  - users 를 읽는 건 user/infrastructure/DbUserDetailsService, BCrypt(12) 비교는 AppLoginProvider(Spring Dao 그대로).
 *  - 성공 200 {id, login_id, display_name, role}, 실패 401 LOGIN_FAILED(사유 숨김), 본문 누락 400 INVALID_REQUEST.
 *  - 필터는 JsonLoginConfigurer 로 등록해 세션 ID 교체·maximumSessions(1)·세션 저장·CSRF 토큰 교체가 Spring 전략으로 걸린다.
 *  - 세션 쿠키, CSRF, 30분 만료, /auth/me, /auth/logout(204), 401/403 JSON.
 *
 * URL 규칙:
 *  /api/v1/auth/login, /actuator/health, swagger → 누구나
 *  /api/v1/admin/**                              → ADMIN
 *  GET  /api/v1/approvals/**                     → ADMIN·APPROVER (10/29)
 *  POST /api/v1/approvals/**                     → APPROVER
 *  /api/**                                       → 로그인만
 *  그 외                                         → 거부
 * "담당 케이스만" 같은 객체 규칙은 여기서 못 막는다 → 유스케이스 안에서 (NotAssigneeException 403, SelfApprovalException 409).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JsonAuthHandlers handlers;
    private final CurrentUser currentUser;
    private final AmlProperties props;
    private final ObjectMapper om;

    public SecurityConfig(JsonAuthHandlers handlers, CurrentUser currentUser, AmlProperties props, ObjectMapper om) {
        this.handlers = handlers;
        this.currentUser = currentUser;
        this.props = props;
        this.om = om;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** 세션 만료·무효화를 SessionRegistry 에 알린다 — 없으면 maximumSessions 가 이미 끝난 세션도 센다. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserDetailsService users, PasswordEncoder encoder) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // 토큰을 매 응답에 쿠키로 내보낸다 (axios 가 읽는다)
        AuthenticationManager login = new ProviderManager(new AppLoginProvider(users, encoder));

        http
            .authenticationManager(login)
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers(JsonLoginConfigurer.LOGIN_URL))   // 로그인 전에는 토큰이 없다
            .cors(c -> c.configurationSource(corsSource()))
            .authorizeHttpRequests(a -> a
                .requestMatchers(JsonLoginConfigurer.LOGIN_URL, "/actuator/health").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/approvals/**").hasAnyRole("ADMIN", "APPROVER")
                .requestMatchers("/api/v1/approvals/**").hasRole("APPROVER")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .sessionManagement(s -> s
                .maximumSessions(1)                              // 같은 계정 두 브라우저면 먼저 것이 끊긴다
                .expiredSessionStrategy(handlers.expiredSession()))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(handlers.entryPoint())
                .accessDeniedHandler(handlers.accessDenied()))
            .with(new JsonLoginConfigurer<>(om), l -> l
                .successHandler(handlers.loginSuccess())
                .failureHandler(handlers.loginFailure()))
            .logout(l -> l
                .logoutUrl("/api/v1/auth/logout")
                .deleteCookies("SESSION")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .addFilterAfter(new UserIdMdcFilter(currentUser), ExceptionTranslationFilter.class);

        return http.build();
    }

    /** local 에서만 프론트(5173) 허용. prod 는 리버스 프록시가 같은 오리진으로 묶으므로 없음. '*' 는 어느 프로파일에도 없다. */
    private CorsConfigurationSource corsSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String origin = props.cors() == null ? null : props.cors().allowedOrigin();
        if (origin != null && !origin.isBlank()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(List.of(origin));
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-Request-Id"));
            cfg.setAllowCredentials(true);
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }
}
