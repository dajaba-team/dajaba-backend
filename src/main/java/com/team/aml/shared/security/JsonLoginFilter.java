package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * POST /api/v1/auth/login {login_id, password} — 폼 대신 JSON 본문을 읽는 것만 다르다.
 * UsernamePasswordAuthenticationFilter 를 상속해 필터 순서·세션 전략(세션 ID 교체, maximumSessions, 컨텍스트 세션 저장)을
 * Spring 이 그대로 넣게 한다. 등록은 JsonLoginConfigurer 로만 — addFilterAt 으로 끼우면 세션 전략이 빠진다.
 */
class JsonLoginFilter extends UsernamePasswordAuthenticationFilter {

    static final String LOGIN_ID_ATTEMPTED = JsonLoginFilter.class.getName() + ".loginIdAttempted";
    /** 비밀번호 비교에 쓴 LoginCandidate — 성공 핸들러가 세션 등록 뒤 DB 를 다시 읽어 비교한다 (SCRUM-135 ②-b). */
    static final String AUTHENTICATED_AS = JsonLoginFilter.class.getName() + ".authenticatedAs";

    record LoginRequest(String loginId, String password) {}

    /** JSON 이 아니거나 본문이 없거나 크거나 필드가 비었다 → 실패 핸들러가 400 INVALID_REQUEST 로 (401 LOGIN_FAILED 와 구분). */
    static class InvalidLoginRequest extends AuthenticationException {
        InvalidLoginRequest() { super("login_id 와 password 가 필요합니다"); }
    }

    private static final int MAX_BODY_BYTES = 4096;      // login_id 64 + password 128 이면 충분. BCrypt 앞에서 큰 본문을 끊는다
    private static final int MAX_LOGIN_ID = 64;          // users.login_id varchar(64)
    private static final int MAX_PASSWORD = 128;
    private static final int MAX_PASSWORD_BYTES = 72;    // BCrypt 가 비교하는 길이 — 넘으면 400 이 아니라 401 (길이 힌트를 주지 않는다)

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
            req.setAttribute(LOGIN_ID_ATTEMPTED, body == null ? null : body.loginId());
        } catch (IOException | InvalidMediaTypeException e) {
            throw new InvalidLoginRequest();
        }
        if (body == null || isBlank(body.loginId()) || isBlank(body.password())
                || body.loginId().length() > MAX_LOGIN_ID || body.password().length() > MAX_PASSWORD) {
            throw new InvalidLoginRequest();
        }
        // BCrypt 는 앞 72바이트만 비교해서, 넘는 비밀번호는 앞 72바이트만 맞아도 통과한다. 발급·초기화도 72바이트까지만 받으니
        // 넘으면 틀린 비밀번호로 본다 — 실패 핸들러가 LOGIN_FAIL 기록 + 401 LOGIN_FAILED 같은 문구(사유를 숨긴다).
        // 계정 조회 전이라 있는 ID·없는 ID 응답 시간이 같다 (SCRUM-135 ②-b)
        if (body.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadCredentialsException("Bad credentials");
        }
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.unauthenticated(body.loginId(), body.password());
        setDetails(req, token);
        Authentication result = getAuthenticationManager().authenticate(token);
        // 비교에 쓴 users 값은 이 요청에만 — 세션에 저장될 인증 객체에는 원래 details 를 돌려 놓는다 (해시가 세션에 남지 않게)
        if (result instanceof AbstractAuthenticationToken t && t.getDetails() instanceof AuthenticatedAs seen) {
            t.setDetails(seen.details());
            req.setAttribute(AUTHENTICATED_AS, seen.candidate());
        }
        return result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
