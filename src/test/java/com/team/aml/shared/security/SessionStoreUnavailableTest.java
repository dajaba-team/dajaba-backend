package com.team.aml.shared.security;

import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis 오류 판별과 UserSessions 의 세션 끊기 실패 (SCRUM-273). */
class SessionStoreUnavailableTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 정상_Redis_오류는_원인_사슬_어디에_있어도_잡고_클래스_이름만() {
        var refused = new RedisConnectionFailureException("Unable to connect to redis:6379");
        var timeout = new QueryTimeoutException("Redis command timed out", new RedisCommandTimeoutException("x"));

        assertThat(SessionStoreUnavailableException.isCausedBy(new RuntimeException(refused))).isTrue();
        assertThat(SessionStoreUnavailableException.errorTypeOf(new RuntimeException(refused)))
                .isEqualTo("RedisConnectionFailureException");
        assertThat(SessionStoreUnavailableException.isCausedBy(timeout)).isTrue();
        assertThat(new SessionStoreUnavailableException(refused).errorType()).isEqualTo("RedisConnectionFailureException");
    }

    @Test
    void 경계_DB_오류는_세션_저장소_오류가_아니다() {
        assertThat(SessionStoreUnavailableException.isCausedBy(new DataIntegrityViolationException("dup"))).isFalse();
        assertThat(SessionStoreUnavailableException.isCausedBy(new QueryTimeoutException("jdbc"))).isFalse();
    }

    @Test
    void 실패_커밋_뒤_Redis를_못_쓰면_SessionStoreUnavailableException() {
        SessionRegistryImpl failing = new SessionRegistryImpl() {
            @Override
            public List<SessionInformation> getAllSessions(Object principal, boolean includeExpired) {
                throw new RedisConnectionFailureException("Unable to connect to redis:6379");
            }
        };
        UserSessions sessions = new UserSessions(failing, id -> Optional.of("inv42"));
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(42L);

        assertThatThrownBy(TransactionSynchronizationUtils::triggerAfterCommit)
                .isInstanceOf(SessionStoreUnavailableException.class)
                .extracting(e -> ((SessionStoreUnavailableException) e).errorType())
                .isEqualTo("RedisConnectionFailureException");
    }

    @Test
    void 정상_Redis_모드처럼_login_id로만_찾는_저장소에도_login_id가_넘어간다() {
        var seen = new java.util.ArrayList<String>();
        SessionRegistryImpl byName = new SessionRegistryImpl() {
            @Override
            public List<SessionInformation> getAllSessions(Object principal, boolean includeExpired) {
                seen.add(((AppUserDetails) principal).getUsername());
                return List.of();
            }
        };
        UserSessions sessions = new UserSessions(byName, id -> Optional.of("inv42"));
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(42L);
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(seen).containsExactly("inv42");
    }
}
