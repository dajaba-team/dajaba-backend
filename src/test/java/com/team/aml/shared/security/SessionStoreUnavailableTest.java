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
                .satisfies(e -> {
                    SessionStoreUnavailableException s = (SessionStoreUnavailableException) e;
                    assertThat(s.errorType()).isEqualTo("RedisConnectionFailureException");
                    assertThat(s.changeSaved()).as("DB 변경은 이미 커밋됨").isTrue();
                    assertThat(s.getMessage()).isEqualTo(SessionStoreUnavailableException.MESSAGE_CHANGE_SAVED);
                });
    }

    @Test
    void 경계_원인_사슬이_A_B_A로_돌아도_끝난다() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(SessionStoreUnavailableException.isCausedBy(a)).isFalse();
        assertThat(SessionStoreUnavailableException.find(a)).isNull();
        assertThat(SessionStoreUnavailableException.errorTypeOf(a)).isEqualTo("RuntimeException");
    }
}
