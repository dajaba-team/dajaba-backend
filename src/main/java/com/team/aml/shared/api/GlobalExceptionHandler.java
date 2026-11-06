package com.team.aml.shared.api;

import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.shared.domain.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예외 → HTTP 변환은 전부 여기 (레퍼런스 8.2). 컨트롤러에 try-catch 는 없다.
 *
 *  @Valid 실패, JSON 파싱 실패, 쿼리 파라미터 오류 → 400 INVALID_REQUEST
 *  *NotFoundException              → 404 <CLASS_NAME>
 *  ForbiddenException 하위         → 403 <CLASS_NAME> (역할·담당자 같은 업무 권한, SCRUM-67)
 *  DomainException 나머지          → 409 <CLASS_NAME>
 *  OptimisticLock                  → 409 CONCURRENT_UPDATE
 *  AiClientException               → e.httpStatus() (기본 502 AI 코드 그대로, 409 GENERATION_IN_PROGRESS→DRAFT_IN_PROGRESS)
 *  AccessDenied (403)              → 403 FORBIDDEN
 *  그 외                            → 500 INTERNAL_ERROR (메시지 고정, 스택은 로그에만)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", fields));
    }

    /**
     * 쿼리·경로 파라미터 오류 — 없는 enum 값, 날짜 형식, @Min·@Max·@Pattern 위반. 처리가 없으면 500 이 된다.
     * 이름을 알 수 있으면 details.parameter 에 넣는다(형 변환·누락). @Min 등 검증 위반은 비운다.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> invalidParameter(Exception e) {
        String parameter = e instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : e instanceof MissingServletRequestParameterException missing ? missing.getParameterName()
                : null;
        Map<String, Object> details = parameter == null ? Map.of() : Map.of("parameter", parameter);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 파라미터가 올바르지 않습니다", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 본문을 읽을 수 없습니다"));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> domainForbidden(ForbiddenException e, HttpServletRequest req) {
        warnDenied(e.code(), req);   // 메시지는 찍지 않는다 — 번호·이름이 들어갈 수 있다
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> domain(DomainException e) {
        log.warn("domain rule violated: {} - {}", e.code(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> concurrent(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONCURRENT_UPDATE", "다른 사용자가 먼저 수정했습니다. 새로 고침하세요"));
    }

    @ExceptionHandler(AiClientException.class)
    public ResponseEntity<ErrorResponse> ai(AiClientException e) {
        log.warn("ai call failed: {} ({})", e.code(), e.httpStatus());   // D5: AI 응답 message 는 로그에 남기지 않는다
        return ResponseEntity.status(e.httpStatus())
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException e, HttpServletRequest req) {
        String code = "FORBIDDEN";
        warnDenied(code, req);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(code, "권한이 없습니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
    }

    /** 403 은 access_logs 에 남기지 않고 경고 한 줄만 (미결 30). JsonAuthHandlers.accessDenied 와 같은 모양. */
    private void warnDenied(String code, HttpServletRequest req) {
        log.warn("access denied code={} method={} uri={}", code, req.getMethod(), req.getRequestURI());
    }
}
