package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseMetrics;
import com.team.aml.cases.application.CaseMetrics.ConflictAction;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * CaseController 요청이 409 CONCURRENT_UPDATE 로 끝나면 aml.cases.concurrent.updates{action} 를 1 센다
 * (06_features/cases.md "케이스 지표", SCRUM-276).
 *
 * <p>컨트롤러 밖에서 거른다 — 유스케이스가 version 을 비교해 던진 것과 @Transactional 커밋 때 Hibernate 가 던진 것을
 * 둘 다 보려면 트랜잭션 프록시 바깥이어야 해서다. 컨트롤러에 try-catch 를 넣지 않는다(01_code_rules §4).
 *
 * <p>순서가 맨 앞이고 <b>늘 null 을 돌려준다</b> — 다음 처리기(GlobalExceptionHandler)가 응답을 그대로 만든다.
 * 응답 모양·공통 에러 지표(aml.http.errors)는 바뀌지 않는다. 세다 실패해도 삼킨다(fail-open).
 */
@Component
public class CaseConflictCounter implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CaseConflictCounter.class);

    /** CaseController 메서드 이름 → action. 메서드 이름을 바꾸면 여기도 (CaseConflictCounterTest 가 잡는다). */
    static final Map<String, ConflictAction> ACTIONS = Map.of(
            "assign", ConflictAction.ASSIGN,
            "reassign", ConflictAction.REASSIGN,
            "escalate", ConflictAction.ESCALATE,
            "close", ConflictAction.CLOSE,
            "refreshSummary", ConflictAction.REFRESH_SUMMARY,
            "updateNotes", ConflictAction.UPDATE_NOTES);

    private final CaseMetrics metrics;

    public CaseConflictCounter(CaseMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        try {
            if (handler instanceof HandlerMethod method && CaseController.class.isAssignableFrom(method.getBeanType())
                    && isOptimisticLock(ex)) {
                metrics.concurrentUpdate(ACTIONS.getOrDefault(method.getMethod().getName(), ConflictAction.OTHER));
            }
        } catch (RuntimeException e) {
            log.warn("case conflict metric failed error_type={}", e.getClass().getSimpleName());
        }
        return null;   // 응답은 GlobalExceptionHandler 몫
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * GlobalExceptionHandler 가 409 CONCURRENT_UPDATE 로 바꾸는 두 예외 — <b>던져진 예외 자체만</b> 본다.
     * 그 처리기에는 Exception 처리가 있어서 Spring 이 원인(cause)까지 내려가지 않는다: 감싼 예외는 500 이라 세면 안 맞는다.
     */
    static boolean isOptimisticLock(Throwable ex) {
        return ex instanceof OptimisticLockException || ex instanceof ObjectOptimisticLockingFailureException;
    }
}
