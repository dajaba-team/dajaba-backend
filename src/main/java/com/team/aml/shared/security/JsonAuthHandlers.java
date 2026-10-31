package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.api.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401·403 도 8.2 의 JSON 형식으로. Spring 기본 HTML·텍스트가 나가면 안 된다 (레퍼런스 9.3).
 *  401 UNAUTHENTICATED — 세션 없음/만료, 다른 곳에서 같은 계정으로 로그인해 끊긴 세션. 프론트는 로그인 화면으로
 *  401 LOGIN_FAILED    — 없는 ID·틀린 비밀번호·비활성·SYSTEM 전부 같은 응답 (사유를 숨긴다)
 *  400 INVALID_REQUEST — 로그인 본문이 없거나 필드가 빔
 *  403 FORBIDDEN       — URL 역할 규칙 불일치. CSRF 불일치도 403 CSRF_INVALID
 */
@Component
public class JsonAuthHandlers {

    /** 로그인 성공 응답 — GET /auth/me(GetMe.Result)와 같은 모양. shared 는 user 를 모르므로 따로 둔다. */
    record LoginResult(Long id, String loginId, String displayName, String role) {}

    private final ObjectMapper om;

    public JsonAuthHandlers(ObjectMapper om) {
        this.om = om;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(res, 401, ErrorResponse.of("UNAUTHENTICATED", "로그인이 필요합니다"));
    }

    /** maximumSessions(1) 로 끊긴 세션의 다음 요청. 기본 전략은 상태 코드 없이 텍스트를 써서 200 으로 나갈 수 있다. */
    public SessionInformationExpiredStrategy expiredSession() {
        return event -> write(event.getResponse(), 401, ErrorResponse.of("UNAUTHENTICATED", "로그인이 필요합니다"));
    }

    public AuthenticationSuccessHandler loginSuccess() {
        return (req, res, auth) -> {
            AppUserDetails me = (AppUserDetails) auth.getPrincipal();
            write(res, 200, new LoginResult(me.userId(), me.loginId(), me.displayName(), me.role()));
        };
    }

    public AuthenticationFailureHandler loginFailure() {
        return (req, res, ex) -> {
            if (ex instanceof JsonLoginFilter.InvalidLoginRequest) {
                write(res, 400, ErrorResponse.of("INVALID_REQUEST", "login_id 와 password 가 필요합니다"));
            } else if (ex instanceof InternalAuthenticationServiceException) {   // DB 장애 등 — 로그인 실패로 숨기지 않는다
                write(res, 500, ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
            } else {
                write(res, 401, ErrorResponse.of("LOGIN_FAILED", "아이디 또는 비밀번호가 맞지 않습니다"));
            }
        };
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> {
            boolean csrf = ex instanceof org.springframework.security.web.csrf.CsrfException;
            write(res, 403, csrf
                    ? ErrorResponse.of("CSRF_INVALID", "CSRF 토큰이 없거나 다릅니다. XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내세요")
                    : ErrorResponse.of("FORBIDDEN", "권한이 없습니다"));
        };
    }

    private void write(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        om.writeValue(res.getWriter(), body);
    }
}
