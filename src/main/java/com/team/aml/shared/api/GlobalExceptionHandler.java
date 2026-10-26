package com.team.aml.shared.api;

import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예외 → HTTP 변환은 전부 여기 (레퍼런스 8.2). 컨트롤러에 try-catch 는 없다.
 *
 *  @Valid 실패, JSON 파싱 실패      → 400 INVALID_REQUEST
 *  *NotFoundException              → 404 <CLASS_NAME>
 *  DomainException 전부            → 409 <CLASS_NAME>
 *  OptimisticLock                  → 409 CONCURRENT_UPDATE
 *  AiClientException               → 502 AI 가 준 코드 그대로
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
        log.warn("ai call failed: {} - {}", e.code(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", "권한이 없습니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
    }
}
