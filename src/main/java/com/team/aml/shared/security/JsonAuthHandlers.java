package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.api.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401·403 도 8.2 의 JSON 형식으로. Spring 기본 HTML 이 나가면 안 된다 (레퍼런스 9.3).
 *  401 UNAUTHENTICATED — 세션 없음/만료. 프론트는 로그인 화면으로
 *  403 FORBIDDEN       — URL 역할 규칙 불일치. CSRF 불일치도 403 CSRF_INVALID
 */
@Component
public class JsonAuthHandlers {

    private final ObjectMapper om;

    public JsonAuthHandlers(ObjectMapper om) {
        this.om = om;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(res, 401, ErrorResponse.of("UNAUTHENTICATED", "로그인이 필요합니다"));
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> {
            boolean csrf = ex instanceof org.springframework.security.web.csrf.CsrfException;
            write(res, 403, csrf
                    ? ErrorResponse.of("CSRF_INVALID", "CSRF 토큰이 없거나 다릅니다. XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내세요")
                    : ErrorResponse.of("FORBIDDEN", "권한이 없습니다"));
        };
    }

    private void write(HttpServletResponse res, int status, ErrorResponse body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        om.writeValue(res.getWriter(), body);
    }
}
