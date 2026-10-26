package com.team.aml.shared.security;

import com.team.aml.shared.config.AmlProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 세션 쿠키 + CSRF + URL 역할 규칙 (레퍼런스 9.3). 두 사람은 이 파일을 고칠 일이 거의 없다.
 *
 * 지금 상태(W1 뼈대): 인증 "절차"만 목업이다.
 *  - local 프로파일에서 POST /api/v1/auth/mock-login?login_id=inv01 이 비밀번호 없이 세션을 만든다 (user/api/MockLoginController).
 *  - 그 뒤는 전부 진짜: 세션 쿠키, CSRF, maximumSessions(1), 30분 만료, /auth/me, /auth/logout, 401/403 JSON.
 *  - 진짜 로그인(POST /auth/login, BCrypt 검사, access_logs LOGIN_OK/FAIL)은 W2 에 진희가 붙인다. 그때 바뀌는 건 로그인 한 줄뿐.
 *
 * URL 규칙:
 *  /api/v1/auth/login, /auth/mock-login, /actuator/health, swagger → 누구나
 *  /api/v1/admin/**                                               → ADMIN
 *  /api/v1/approvals/**                                           → APPROVER
 *  /api/**                                                        → 로그인만
 *  그 외                                                          → 거부
 * "담당 케이스만" 같은 객체 규칙은 여기서 못 막는다 → 유스케이스 안에서 (NotAssigneeException 403, SelfApprovalException 409).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JsonAuthHandlers handlers;
    private final CurrentUser currentUser;
    private final AmlProperties props;

    public SecurityConfig(JsonAuthHandlers handlers, CurrentUser currentUser, AmlProperties props) {
        this.handlers = handlers;
        this.currentUser = currentUser;
        this.props = props;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 기본 인메모리 사용자("Using generated security password" 경고) 생성을 막는다.
     * W2 진짜 로그인에서 users 테이블을 읽는 구현(DbUserDetailsService)으로 교체한다. 지금은 아무도 못 찾는다 — 로그인은 mock-login 뿐.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> { throw new UsernameNotFoundException("W2 에 DbUserDetailsService 로 교체"); };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // 토큰을 매 응답에 쿠키로 내보낸다 (axios 가 읽는다)

        http
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers("/api/v1/auth/login", "/api/v1/auth/mock-login"))   // 로그인 전에는 토큰이 없다
            .cors(c -> c.configurationSource(corsSource()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/mock-login", "/actuator/health").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/approvals/**").hasRole("APPROVER")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .sessionManagement(s -> s.maximumSessions(1))   // 같은 계정 두 브라우저면 먼저 것이 끊긴다
            .exceptionHandling(e -> e
                .authenticationEntryPoint(handlers.entryPoint())
                .accessDeniedHandler(handlers.accessDenied()))
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
