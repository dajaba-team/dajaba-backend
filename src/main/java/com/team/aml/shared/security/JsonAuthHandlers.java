package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.audit.LoginAccessLogWriter;
import com.team.aml.shared.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

/**
 * 401·403 도 8.2 의 JSON 형식으로. Spring 기본 HTML·텍스트가 나가면 안 된다 (레퍼런스 9.3).
 *  401 UNAUTHENTICATED — 세션 없음/만료, 다른 곳에서 같은 계정으로 로그인해 끊긴 세션. 프론트는 로그인 화면으로
 *  401 LOGIN_FAILED    — 없는 ID·틀린 비밀번호·비활성·SYSTEM·비밀번호 UTF-8 72바이트 초과 전부 같은 응답 (사유를 숨긴다)
 *  400 INVALID_REQUEST — 로그인 본문이 없거나 필드가 빔
 *  403 FORBIDDEN       — URL 역할 규칙 불일치. CSRF 불일치도 403 CSRF_INVALID
 */
@Component
public class JsonAuthHandlers {

    private static final Logger log = LoggerFactory.getLogger(JsonAuthHandlers.class);
    private final LoginAccessLogWriter accessLog;

    /** 로그인 성공 응답 — GET /auth/me(GetMe.Result)와 같은 모양. shared 는 user 를 모르므로 따로 둔다. */
    record LoginResult(Long id, String loginId, String displayName, String role) {}

    private final ObjectMapper om;
    private final CurrentUser currentUser;
    private final UserDetailsService users;

    public JsonAuthHandlers(ObjectMapper om, LoginAccessLogWriter accessLog, CurrentUser currentUser, UserDetailsService users) {
        this.om = om;
        this.accessLog = accessLog;
        this.currentUser = currentUser;
        this.users = users;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(res, 401, ErrorResponse.of("UNAUTHENTICATED", "로그인이 필요합니다"));
    }

    /** maximumSessions(1) 로 끊긴 세션의 다음 요청. 기본 전략은 상태 코드 없이 텍스트를 써서 200 으로 나갈 수 있다. */
    public SessionInformationExpiredStrategy expiredSession() {
        return event -> write(event.getResponse(), 401, ErrorResponse.of("UNAUTHENTICATED", "로그인이 필요합니다"));
    }

    /**
     * 로그인 성공 — 먼저 세션 등록 뒤 재확인 (SCRUM-135 ②-b).
     * Spring(AbstractAuthenticationProcessingFilter)은 세션 등록(sessionStrategy → SessionRegistry)을 마친 다음 이 핸들러를 부른다.
     * 관리자 변경(역할·비활성화·초기화)은 커밋 뒤 UserSessions.expireAll 로 그 사람 세션을 끊는데, 로그인이 비밀번호 비교를
     * 옛 값으로 끝낸 사이에 커밋되면 두 순서가 있다:
     *  - 세션 등록이 expireAll 보다 먼저 → expireAll 이 이 세션도 끊는다
     *  - 세션 등록이 expireAll 보다 나중 → 커밋이 이미 끝났으니 여기서 다시 읽으면 바뀐 값이 보인다 → 이 로그인을 취소
     * 그래서 활성·역할·해시가 비교 때와 다르면 세션을 버리고 틀린 비밀번호와 같은 401 LOGIN_FAILED + LOGIN_FAIL.
     */
    public AuthenticationSuccessHandler loginSuccess() {
        return (req, res, auth) -> {
            AppUserDetails me = (AppUserDetails) auth.getPrincipal();
            boolean same;
            try {
                same = stillSameAsChecked((LoginCandidate) req.getAttribute(JsonLoginFilter.AUTHENTICATED_AS));
            } catch (RuntimeException e) {   // DB 장애 등 — 로그인 실패로 숨기지 않되 세션은 남기지 않는다
                undoLogin(req);
                log.warn("login recheck failed error={}", e.getClass().getSimpleName());   // 입력 login_id 는 남기지 않는다
                write(res, 500, ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
                return;
            }
            if (!same) {
                undoLogin(req);
                loginFailed(req, res);
                return;
            }
            try {
                accessLog.loginOk(me.userId(), req.getRemoteAddr());
            } catch (RuntimeException e) {
                log.warn("access log write failed action={}", "LOGIN_OK");
            }
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
                loginFailed(req, res);
            }
        };
    }

    /** 틀린 비밀번호와 같은 응답 — LOGIN_FAIL 한 행 + 401 LOGIN_FAILED. */
    private void loginFailed(HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            accessLog.loginFail((String) req.getAttribute(JsonLoginFilter.LOGIN_ID_ATTEMPTED), req.getRemoteAddr());
        } catch (RuntimeException e) {
            log.warn("access log write failed action={}", "LOGIN_FAIL");
        }
        write(res, 401, ErrorResponse.of("LOGIN_FAILED", "아이디 또는 비밀번호가 맞지 않습니다"));
    }

    /** users 를 다시 읽어 비밀번호 비교 때와 활성·역할·해시가 같은지. 비활성·SYSTEM 이면 DbUserDetailsService 가 "없음"으로 던진다. */
    private boolean stillSameAsChecked(LoginCandidate checked) {
        if (checked == null) return false;
        try {
            return users.loadUserByUsername(checked.getUsername()) instanceof LoginCandidate now
                    && Objects.equals(now.user().userId(), checked.user().userId())
                    && Objects.equals(now.user().role(), checked.user().role())
                    && Objects.equals(now.passwordHash(), checked.passwordHash());
        } catch (UsernameNotFoundException e) {
            return false;
        }
    }

    /** Spring 이 이미 저장한 로그인을 되돌린다 — 세션 무효화(HttpSessionEventPublisher 가 SessionRegistry 에서도 뺀다) + 컨텍스트 비움. */
    private static void undoLogin(HttpServletRequest req) {
        SecurityContextHolder.clearContext();
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> {
            boolean csrf = ex instanceof org.springframework.security.web.csrf.CsrfException;
            String code = csrf ? "CSRF_INVALID" : "FORBIDDEN";
            // CsrfFilter 는 UserIdMdcFilter 앞이라 아직 user_id 가 없다. 지우는 건 RequestIdFilter finally.
            if (MDC.get("user_id") == null) {
                currentUser.get().ifPresent(u -> MDC.put("user_id", String.valueOf(u.userId())));
            }
            // 403 은 access_logs 에 남기지 않고 경고 한 줄만 (미결 30). getRequestURI 는 쿼리스트링을 포함하지 않는다.
            log.warn("access denied code={} method={} uri={}", code, req.getMethod(), req.getRequestURI());
            write(res, 403, ErrorResponse.of(code, csrf
                    ? "CSRF 토큰이 없거나 다릅니다. XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내세요"
                    : "권한이 없습니다"));
        };
    }

    private void write(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        om.writeValue(res.getWriter(), body);
    }
}
