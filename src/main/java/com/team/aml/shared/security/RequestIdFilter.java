package com.team.aml.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 request_id 를 만들어 MDC 에 넣는다 (레퍼런스 8.6). 모든 로그 줄에 자동으로 붙는다.
 * 응답 헤더 X-Request-Id 로도 돌려줘서 프론트가 에러 신고할 때 적을 수 있다.
 * X-Request-Id는 1~64자 [A-Za-z0-9._-]만 허용하며, 없거나 빈 값은 조용히 UUID로, 그 밖은 UUID로 바꾸고 길이만 경고한다 (SCRUM-272).
 * user_id 는 인증 뒤에 UserIdMdcFilter 가 넣는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final int MAX_LEN = 64;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String raw = req.getHeader(HEADER);
        String id;
        if (raw == null || raw.isEmpty()) {
            id = UUID.randomUUID().toString();
        } else if (raw.length() <= MAX_LEN && REQUEST_ID_PATTERN.matcher(raw).matches()) {
            id = raw;
        } else {
            id = UUID.randomUUID().toString();
            log.warn("request_id_replaced received_len={}", raw.length());
        }
        MDC.put("request_id", id);
        res.setHeader(HEADER, id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("request_id");
            MDC.remove("user_id");
        }
    }
}
