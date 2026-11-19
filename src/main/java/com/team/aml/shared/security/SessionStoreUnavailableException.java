package com.team.aml.shared.security;

import io.lettuce.core.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/**
 * 세션 저장소(Redis)를 못 써서 요청을 처리할 수 없음 → 503 SESSION_STORE_UNAVAILABLE (SCRUM-273, 03_shared §2).
 * 연결 실패·명령 시간 초과·쓰기 거부(maxmemory noeviction 의 OOM) 모두. Redis 에는 세션만 있으니 Redis 오류 = 세션 저장소 오류.
 * 로그에는 {@link #errorType()}(클래스 이름)만 — 원인 메시지에는 호스트·포트가 들어 있다.
 */
public class SessionStoreUnavailableException extends RuntimeException {

    public static final String CODE = "SESSION_STORE_UNAVAILABLE";
    public static final String MESSAGE = "로그인 세션 저장소에 연결할 수 없습니다. 잠시 뒤 다시 시도하세요";

    private final String errorType;

    public SessionStoreUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
        this.errorType = errorTypeOf(cause);
    }

    public String errorType() {
        return errorType;
    }

    /** 원인 사슬에 Redis 오류가 있나. 세션 모드와 상관없이 불러도 된다(메모리 모드에선 항상 false). */
    public static boolean isCausedBy(Throwable e) {
        return redisError(e) != null;
    }

    /** 원인 사슬에서 처음 만난 Redis 오류의 클래스 이름 (예: RedisConnectionFailureException, RedisSystemException). */
    public static String errorTypeOf(Throwable e) {
        Throwable redis = redisError(e);
        return (redis == null ? e : redis).getClass().getSimpleName();
    }

    private static Throwable redisError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SessionStoreUnavailableException s) {
                return s.getCause();
            }
            if (t instanceof RedisConnectionFailureException || t instanceof RedisSystemException || t instanceof RedisException) {
                return t;
            }
        }
        return null;
    }
}
