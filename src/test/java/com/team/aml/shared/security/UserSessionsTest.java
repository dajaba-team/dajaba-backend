package com.team.aml.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UserSessions (SCRUM-135 ②) — 그 사람 세션만, 커밋 뒤에만 끊는다. */
class UserSessionsTest {

    SessionRegistryImpl registry = new SessionRegistryImpl();
    UserSessions sessions = new UserSessions(registry);

    @BeforeEach
    void setUp() {
        // 로그인 때 들어가는 주체와 같은 모양 — 역할·이름이 있어도 userId 로만 같은 사람
        registry.registerNewSession("target-1", new AppUserDetails(42L, "inv42", "조사역", "INVESTIGATOR"));
        registry.registerNewSession("target-2", new AppUserDetails(42L, "inv42", "조사역", "INVESTIGATOR"));
        registry.registerNewSession("other", new AppUserDetails(7L, "admin", "관리자", "ADMIN"));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 정상_커밋_뒤에_그_사람_세션만_전부_끊긴다() {
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(42L);
        assertThat(expired("target-1")).as("커밋 전").isFalse();

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(expired("target-1")).isTrue();
        assertThat(expired("target-2")).isTrue();
        assertThat(expired("other")).isFalse();
    }

    @Test
    void 실패_롤백이면_끊지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(42L);

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(expired("target-1")).isFalse();
        assertThat(expired("target-2")).isFalse();
    }

    @Test
    void 실패_트랜잭션_밖이면_바로_IllegalStateException이고_끊지_않는다() {
        assertThatThrownBy(() -> sessions.expireAll(42L)).isInstanceOf(IllegalStateException.class);
        assertThat(expired("target-1")).isFalse();
    }

    @Test
    void 경계_세션이_없는_사람이면_아무것도_안_한다() {
        TransactionSynchronizationManager.initSynchronization();
        sessions.expireAll(999L);
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(expired("target-1")).isFalse();
        assertThat(expired("other")).isFalse();
    }

    private boolean expired(String sessionId) {
        return registry.getSessionInformation(sessionId).isExpired();
    }
}
