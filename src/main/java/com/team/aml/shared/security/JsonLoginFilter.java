package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * POST /api/v1/auth/login {login_id, password} — 폼 대신 JSON 본문을 읽는 것만 다르다.
 * UsernamePasswordAuthenticationFilter 를 상속해 필터 순서·세션 전략(세션 ID 교체, maximumSessions, 컨텍스트 세션 저장)을
 * Spring 이 그대로 넣게 한다. 등록은 JsonLoginConfigurer 로만 — addFilterAt 으로 끼우면 세션 전략이 빠진다.
 */
class JsonLoginFilter extends UsernamePasswordAuthenticationFilter {

    record LoginRequest(String loginId, String password) {}

    /** JSON 이 아니거나 본문이 없거나 크거나 필드가 비었다 → 실패 핸들러가 400 INVALID_REQUEST 로 (401 LOGIN_FAILED 와 구분). */
    static class InvalidLoginRequest extends AuthenticationException {
        InvalidLoginRequest() { super("login_id 와 password 가 필요합니다"); }
    }

    private static final int MAX_BODY_BYTES = 4096;      // login_id 64 + password 128 이면 충분. BCrypt 앞에서 큰 본문을 끊는다
    private static final int MAX_LOGIN_ID = 64;          // users.login_id varchar(64)
    private static final int MAX_PASSWORD = 128;

    private final ObjectMapper om;

    JsonLoginFilter(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest req, HttpServletResponse res) {
        LoginRequest body;
        try {
            // application/json 만 — text/plain 폼 같은 "단순 요청"으로 남의 브라우저를 로그인시키는 로그인 CSRF 를 막는다
            if (req.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(req.getContentType()))) {
                throw new InvalidLoginRequest();
            }
            byte[] raw = req.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) throw new InvalidLoginRequest();
            body = om.readValue(raw, LoginRequest.class);
        } catch (IOException | InvalidMediaTypeException e) {
            throw new InvalidLoginRequest();
        }
        if (body == null || isBlank(body.loginId()) || isBlank(body.password())
                || body.loginId().length() > MAX_LOGIN_ID || body.password().length() > MAX_PASSWORD) {
            throw new InvalidLoginRequest();
        }
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.unauthenticated(body.loginId(), body.password());
        setDetails(req, token);
        return getAuthenticationManager().authenticate(token);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
