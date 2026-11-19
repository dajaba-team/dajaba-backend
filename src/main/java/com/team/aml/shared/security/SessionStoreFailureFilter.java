package com.team.aml.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aml.shared.api.ErrorResponse;
import com.team.aml.shared.metrics.ErrorMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis 세션 모드에서 세션 읽기·저장이 실패하면 하얀 오류 페이지 대신 공통 에러 JSON 503 SESSION_STORE_UNAVAILABLE (SCRUM-273).
 * Spring Session 필터 바로 바깥에 있어서(RedisSessionConfig) 보안 필터·컨트롤러·세션 저장(요청 끝) 어디서 난 Redis 오류든 여기로 온다.
 * 로그는 WARN 한 줄, 오류 클래스 이름만 — 원인 메시지(호스트·포트)·세션 ID·쿠키는 남기지 않는다.
 * 응답이 이미 나간 뒤면 바꿀 수 없어 로그만 남긴다.
 * 응답을 비울 때 X-Request-Id 와 CORS 헤더(보안 필터 안쪽에서 붙음)는 다시 붙인다 — 없으면 다른 출처의 프론트가 503 본문을 못 읽는다.
 */
class SessionStoreFailureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreFailureFilter.class);

    private final ObjectMapper om;
    private final ErrorMetrics errorMetrics;

    SessionStoreFailureFilter(ObjectMapper om, ErrorMetrics errorMetrics) {
        this.om = om;
        this.errorMetrics = errorMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!SessionStoreUnavailableException.isCausedBy(e)) {
                throw e;
            }
            String errorType = SessionStoreUnavailableException.errorTypeOf(e);
            if (res.isCommitted()) {
                log.warn("session store unavailable after response committed error={}", errorType);
                return;
            }
            log.warn("session store unavailable error={} method={} uri={}", errorType, req.getMethod(), req.getRequestURI());
            List<String[]> kept = keptHeaders(res);
            res.reset();   // 실패한 세션의 Set-Cookie 등을 지운다
            for (String[] h : kept) {
                res.addHeader(h[0], h[1]);
            }
            SessionStoreUnavailableException wrapped = SessionStoreUnavailableException.find(e);
            boolean changeSaved = wrapped != null && wrapped.changeSaved();
            errorMetrics.record(SessionStoreUnavailableException.CODE, 503);
            res.setStatus(503);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            om.writeValue(res.getWriter(), changeSaved
                    ? ErrorResponse.of(SessionStoreUnavailableException.CODE,
                            SessionStoreUnavailableException.MESSAGE_CHANGE_SAVED, Map.of("change_saved", true))
                    : ErrorResponse.of(SessionStoreUnavailableException.CODE, SessionStoreUnavailableException.MESSAGE));
        }
    }

    /** reset 뒤에 다시 붙일 헤더 — X-Request-Id, CORS(Access-Control-*), 그리고 CORS 가 붙이는 Vary. */
    private static List<String[]> keptHeaders(HttpServletResponse res) {
        List<String[]> kept = new ArrayList<>();
        for (String name : res.getHeaderNames().stream().distinct().toList()) {
            if (name.equalsIgnoreCase(RequestIdFilter.HEADER) || name.equalsIgnoreCase("Vary")
                    || name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())) {
                for (String value : res.getHeaders(name)) {
                    kept.add(new String[] {name, value});
                }
            }
        }
        return kept;
    }
}
