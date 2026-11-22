package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseMetrics;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 케이스 변경 API 의 409 CONCURRENT_UPDATE 건수 {@code aml.cases.conflicts{action}} (SCRUM-276).
 *
 * <p>요청이 끝난 뒤(afterCompletion) {@link CountsConflicts}가 붙은 핸들러만 본다. 예외는 GlobalExceptionHandler 가
 * 응답으로 바꾼 뒤라 인자로 안 오고, DispatcherServlet 이 남긴 요청 속성({@link DispatcherServlet#EXCEPTION_ATTRIBUTE})에 있다.
 * 그 예외가 GlobalExceptionHandler 의 CONCURRENT_UPDATE 두 종류일 때만 센다 — 화면 version 비교(유스케이스 안)와
 * 커밋 때 @Version 충돌(트랜잭션 밖) 둘 다 여기로 온다. 상태 전이 409·403·404 는 안 센다.
 *
 * <p>컨트롤러에 try-catch 를 두지 않으려고(01_code_rules §4) 인터셉터로 센다. 세다가 예외가 나도 응답은 그대로(CaseMetrics fail-open).
 */
@Configuration
public class CaseConflictCounter implements HandlerInterceptor, WebMvcConfigurer {

    private final CaseMetrics metrics;

    public CaseConflictCounter(CaseMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/cases/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        CountsConflicts mark = method.getMethodAnnotation(CountsConflicts.class);
        if (mark == null) {
            return;
        }
        Object error = ex != null ? ex : request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        if (isConcurrentUpdate(error)) {
            metrics.conflict(mark.value());
        }
    }

    /** GlobalExceptionHandler 가 409 CONCURRENT_UPDATE 로 바꾸는 예외인가. @ExceptionHandler 처럼 cause 도 본다. */
    static boolean isConcurrentUpdate(Object error) {
        Throwable t = error instanceof Throwable th ? th : null;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof OptimisticLockException || t instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }
}
