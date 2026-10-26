package com.team.aml.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 인증이 끝난 뒤 user_id 를 MDC 에 넣는다. SecurityConfig 가 필터 체인 안에 끼운다. */
public class UserIdMdcFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;

    public UserIdMdcFilter(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        currentUser.get().ifPresent(u -> MDC.put("user_id", String.valueOf(u.userId())));
        chain.doFilter(req, res);
    }
}
