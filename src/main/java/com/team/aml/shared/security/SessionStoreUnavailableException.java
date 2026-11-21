package com.team.aml.shared.security;

import io.lettuce.core.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/**
 * 세션 저장소(Redis)를 못 써서 요청을 처리할 수 없음 → 503 SESSION_STORE_UNAVAILABLE (SCRUM-273, 03_shared §2).
 * 연결 실패·명령 시간 초과·쓰기 거부(maxmemory noeviction 의 OOM) 모두. Redis 에는 세션만 있으니 Redis 오류 = 세션 저장소 오류.
 * 로그에는 {@link #errorType()}(클래스 이름)만 — 원인 메시지에는 호스트·포트가 들어 있다.
 * 관리자 변경이 커밋된 뒤 세션 끊기만 실패했으면 {@link #afterCommit} — 같은 코드에 다른 메시지·{@code details.change_saved=true}.
 */
public class SessionStoreUnavailableException extends RuntimeException {

    public static final String CODE = "SESSION_STORE_UNAVAILABLE";
    public static final String MESSAGE = "로그인 세션 저장소에 연결할 수 없습니다. 잠시 뒤 다시 시도하세요";
    public static final String MESSAGE_CHANGE_SAVED =
            "변경은 저장됐지만 세션 저장소에 연결할 수 없어 그 사용자의 로그인을 끊지 못했습니다";
    /** 원인 사슬을 이만큼만 본다 — A→B→A 처럼 도는 사슬에서도 끝나게. */
    static final int MAX_CAUSE_DEPTH = 20;

    private final String errorType;
    private final boolean changeSaved;

    public SessionStoreUnavailableException(Throwable cause) {
        this(cause, false);
    }

    private SessionStoreUnavailableException(Throwable cause, boolean changeSaved) {
        super(changeSaved ? MESSAGE_CHANGE_SAVED : MESSAGE, cause);
        this.errorType = errorTypeOf(cause);
        this.changeSaved = changeSaved;
    }

    /** DB 변경은 커밋됐고 세션 끊기만 실패 — 관리자 화면이 "실패"로 오해하지 않게 (#144 리뷰). */
    public static SessionStoreUnavailableException afterCommit(Throwable cause) {
        return new SessionStoreUnavailableException(cause, true);
    }

    public String errorType() {
        return errorType;
    }

    public boolean changeSaved() {
        return changeSaved;
    }

    /** 원인 사슬에 이미 감싼 예외가 있으면 그것. 없으면 null. */
    public static SessionStoreUnavailableException find(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof SessionStoreUnavailableException s) {
                return s;
            }
        }
        return null;
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
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
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
