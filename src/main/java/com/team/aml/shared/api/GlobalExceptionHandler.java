package com.team.aml.shared.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.team.aml.shared.ai.AiClientException;
import com.team.aml.shared.domain.DomainException;
import com.team.aml.shared.domain.InvalidRequestException;
import com.team.aml.shared.domain.ForbiddenException;
import com.team.aml.shared.domain.NotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 예외 → HTTP 변환은 전부 여기 (레퍼런스 8.2). 컨트롤러에 try-catch 는 없다.
 *
 *  @Valid 실패, JSON 파싱 실패, 쿼리 파라미터 오류 → 400 INVALID_REQUEST (details 키는 요청 JSON 이름 snake_case — SCRUM-167)
 *  InvalidRequestException        → 400 INVALID_REQUEST (지금 값과 비교해야 아는 입력 오류, details 그대로 — SCRUM-135 ②)
 *  *NotFoundException              → 404 <CLASS_NAME>
 *  ForbiddenException 하위         → 403 <CLASS_NAME> (역할·담당자 같은 업무 권한, SCRUM-67)
 *  DomainException 나머지          → 409 <CLASS_NAME>
 *  OptimisticLock                  → 409 CONCURRENT_UPDATE
 *  AiClientException               → e.httpStatus() (기본 502 AI 코드 그대로, 409 GENERATION_IN_PROGRESS→DRAFT_IN_PROGRESS, 이름 겹치는 AI 4xx 셋은 AI_ 붙임)
 *  AccessDenied (403)              → 403 FORBIDDEN
 *  NoResourceFoundException        → 404 NOT_FOUND (없는 경로, details {}, 경고 한 줄 — SCRUM-157)
 *  HttpRequestMethodNotSupported   → 405 METHOD_NOT_ALLOWED (Allow 헤더, 모르면 없음, 경고 한 줄 — SCRUM-157)
 *  그 외                            → 500 INTERNAL_ERROR (진짜 처리 안 된 것만. 메시지 고정, 스택은 로그에만)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** application.yml property-naming-strategy: SNAKE_CASE 와 같은 규칙 — 요청·응답 직렬화와 이름이 어긋나지 않게. */
    private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    /**
     * 본문·@ModelAttribute 쿼리의 @Valid 실패. details 키는 요청 JSON 이름(snake_case, 규약 0절) — 필드 경로를 조각마다
     * Jackson 과 같은 규칙으로 바꾸고 [0]·. 은 그대로(items[0].transactionId → items[0].transaction_id).
     * 한 필드에 위반이 여럿이면 첫 번째만. 클래스 수준 검증(global error)은 지금 없어서 담지 않는다 (SCRUM-167).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(toJsonPath(fe.getField()), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", fields));
    }

    /**
     * 쿼리·경로 파라미터 오류 — 없는 enum 값, 날짜 형식, @Min·@Max·@Pattern 위반. 처리가 없으면 500 이 된다.
     * 틀린 파라미터 이름을 details.parameter 에 넣는다. 검증 위반이 여러 개면 첫 번째만.
     * 검증 위반은 @RequestParam·@PathVariable 에 이름이 있으면 그 이름, 없으면 변수 이름 (SCRUM-167).
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> invalidParameter(Exception e) {
        String parameter = e instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : e instanceof MissingServletRequestParameterException missing ? missing.getParameterName()
                : e instanceof HandlerMethodValidationException invalid
                        ? invalid.getAllValidationResults().stream().findFirst()
                                .map(r -> requestName(r.getMethodParameter())).orElse(null)
                : null;
        Map<String, Object> details = parameter == null ? Map.of() : Map.of("parameter", parameter);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 파라미터가 올바르지 않습니다", details));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", e.details()));
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

    /** 없는 경로 — Spring 6.1 은 매핑이 없으면 정적 리소스로 찾다 이 예외를 던진다. 경로는 응답에 다시 넣지 않는다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e, HttpServletRequest req) {
        log.warn("not found method={} uri={}", req.getMethod(), req.getRequestURI());   // 쿼리스트링은 찍지 않는다
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "요청한 주소가 없습니다"));
    }

    /** 경로는 있는데 메서드가 틀림. 허용 메서드를 Spring 이 알면 Allow 헤더로, 모르면(null) 헤더 없이. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        log.warn("method not allowed method={} uri={}", req.getMethod(), req.getRequestURI());
        ResponseEntity.BodyBuilder res = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        if (allowed != null) {
            res.allow(allowed.toArray(HttpMethod[]::new));
        }
        return res.body(ErrorResponse.of("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "알 수 없는 오류"));
    }

    /** "items[0].transactionId" → "items[0].transaction_id". 조각("." 사이)마다 이름 부분만 바꾸고 "[...]" 는 그대로. */
    private static String toJsonPath(String field) {
        StringBuilder out = new StringBuilder(field.length() + 8);
        for (String segment : field.split("\\.", -1)) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            int bracket = segment.indexOf('[');
            String name = bracket < 0 ? segment : segment.substring(0, bracket);
            out.append(SNAKE_CASE.translate(name)).append(bracket < 0 ? "" : segment.substring(bracket));
        }
        return out.toString();
    }

    /** @RequestParam·@PathVariable 의 name(=value) 이 있으면 그것, 없으면 변수 이름. */
    private static String requestName(MethodParameter p) {
        RequestParam rp = p.getParameterAnnotation(RequestParam.class);
        if (rp != null && !rp.name().isEmpty()) {
            return rp.name();
        }
        PathVariable pv = p.getParameterAnnotation(PathVariable.class);
        if (pv != null && !pv.name().isEmpty()) {
            return pv.name();
        }
        return p.getParameterName();
    }

    /** 403 은 access_logs 에 남기지 않고 경고 한 줄만 (미결 30). JsonAuthHandlers.accessDenied 와 같은 모양. */
    private void warnDenied(String code, HttpServletRequest req) {
        log.warn("access denied code={} method={} uri={}", code, req.getMethod(), req.getRequestURI());
    }
}
